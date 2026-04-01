package collector

import (
	"context"
	"fmt"
	"log"
	"time"

	autoscalingv2 "k8s.io/api/autoscaling/v2"
	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	networkingv1 "k8s.io/api/networking/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	metricsv "k8s.io/metrics/pkg/client/clientset/versioned"
)

// collectK8sResources gathers a comprehensive snapshot of all Kubernetes resources.
// This replaces the old collectClusterMetadata and is the primary K8s data source.
func collectK8sResources(ctx context.Context, k8s kubernetes.Interface, mc metricsv.Interface) (DataBatch, error) {
	resources := K8sResources{}

	// ── Kubernetes Server Version ─────────────────────────────────────────────
	if sv, err := k8s.Discovery().ServerVersion(); err != nil {
		log.Printf("WARN: k8s-resources get server version: %v", err)
	} else {
		resources.K8sVersion = sv.GitVersion
	}

	// ── Nodes ────────────────────────────────────────────────────────────────
	nodes, err := k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{})
	if err != nil {
		return DataBatch{}, fmt.Errorf("list nodes: %w", err)
	}

	// Build node capacity map for metric percent calculations
	nodeCPUCapMillis := map[string]int64{}
	nodeMemCapBytes := map[string]int64{}

	for _, n := range nodes.Items {
		nodeCPUCapMillis[n.Name] = n.Status.Capacity.Cpu().MilliValue()
		nodeMemCapBytes[n.Name] = n.Status.Capacity.Memory().Value()
		resources.Nodes = append(resources.Nodes, buildNodeDetail(n))
		resources.Summary.TotalNodes++
		if isNodeReady(n) {
			resources.Summary.ReadyNodes++
		}
		if isSpotNode(n) {
			resources.Summary.SpotNodes++
		}
	}

	// ── Namespaces ───────────────────────────────────────────────────────────
	nsList, err := k8s.CoreV1().Namespaces().List(ctx, metav1.ListOptions{})
	if err != nil {
		return DataBatch{}, fmt.Errorf("list namespaces: %w", err)
	}
	nsPodCount := map[string]int{}
	nsRunningCount := map[string]int{}
	for _, ns := range nsList.Items {
		resources.Namespaces = append(resources.Namespaces, NamespaceDetail{
			Name:    ns.Name,
			Labels:  ns.Labels,
			Status:  string(ns.Status.Phase),
			Age:     ns.CreationTimestamp.UTC().Format(time.RFC3339),
		})
		resources.Summary.TotalNamespaces++
		nsPodCount[ns.Name] = 0
	}

	// ── Pods ─────────────────────────────────────────────────────────────────
	podList, err := k8s.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
	if err != nil {
		return DataBatch{}, fmt.Errorf("list pods: %w", err)
	}
	nsDeployCount := map[string]int{}
	for _, p := range podList.Items {
		pd := buildPodDetail(p)
		resources.Pods = append(resources.Pods, pd)
		nsPodCount[p.Namespace]++
		resources.Summary.TotalPods++
		switch p.Status.Phase {
		case corev1.PodRunning:
			resources.Summary.RunningPods++
			nsRunningCount[p.Namespace]++
		case corev1.PodPending:
			resources.Summary.PendingPods++
			if pd.Unschedulable {
				resources.Summary.UnscheduledPods++
			}
		case corev1.PodFailed:
			resources.Summary.FailedPods++
		}
	}
	// Back-fill namespace pod counts
	for i, ns := range resources.Namespaces {
		resources.Namespaces[i].PodCount = nsPodCount[ns.Name]
		resources.Namespaces[i].RunningPodCount = nsRunningCount[ns.Name]
		resources.Namespaces[i].DeploymentCount = nsDeployCount[ns.Name]
	}

	// ── Deployments ──────────────────────────────────────────────────────────
	deplList, err := k8s.AppsV1().Deployments("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list deployments: %v", err)
	} else {
		for _, d := range deplList.Items {
			nsDeployCount[d.Namespace]++
			images := collectImages(d.Spec.Template.Spec.Containers)
			resources.Deployments = append(resources.Deployments, DeploymentDetail{
				Name:              d.Name,
				Namespace:         d.Namespace,
				Labels:            d.Labels,
				Replicas:          derefInt32(d.Spec.Replicas),
				ReadyReplicas:     d.Status.ReadyReplicas,
				AvailableReplicas: d.Status.AvailableReplicas,
				UpdatedReplicas:   d.Status.UpdatedReplicas,
				Strategy:          string(d.Spec.Strategy.Type),
				Age:               d.CreationTimestamp.UTC().Format(time.RFC3339),
				Images:            images,
			})
			resources.Summary.TotalDeployments++
		}
	}

	// ── StatefulSets ─────────────────────────────────────────────────────────
	ssList, err := k8s.AppsV1().StatefulSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list statefulsets: %v", err)
	} else {
		for _, ss := range ssList.Items {
			images := collectImages(ss.Spec.Template.Spec.Containers)
			resources.StatefulSets = append(resources.StatefulSets, StatefulSetDetail{
				Name:          ss.Name,
				Namespace:     ss.Namespace,
				Labels:        ss.Labels,
				Replicas:      derefInt32(ss.Spec.Replicas),
				ReadyReplicas: ss.Status.ReadyReplicas,
				ServiceName:   ss.Spec.ServiceName,
				Age:           ss.CreationTimestamp.UTC().Format(time.RFC3339),
				Images:        images,
			})
		}
	}

	// ── DaemonSets ───────────────────────────────────────────────────────────
	dsList, err := k8s.AppsV1().DaemonSets("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list daemonsets: %v", err)
	} else {
		for _, ds := range dsList.Items {
			images := collectImages(ds.Spec.Template.Spec.Containers)
			resources.DaemonSets = append(resources.DaemonSets, DaemonSetDetail{
				Name:                   ds.Name,
				Namespace:              ds.Namespace,
				Labels:                 ds.Labels,
				DesiredNumberScheduled: ds.Status.DesiredNumberScheduled,
				NumberReady:            ds.Status.NumberReady,
				NumberUnavailable:      ds.Status.NumberUnavailable,
				Age:                    ds.CreationTimestamp.UTC().Format(time.RFC3339),
				Images:                 images,
			})
		}
	}

	// ── Services ─────────────────────────────────────────────────────────────
	svcList, err := k8s.CoreV1().Services("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list services: %v", err)
	} else {
		for _, svc := range svcList.Items {
			ports := make([]ServicePort, 0, len(svc.Spec.Ports))
			for _, p := range svc.Spec.Ports {
				ports = append(ports, ServicePort{
					Name:       p.Name,
					Port:       p.Port,
					TargetPort: p.TargetPort.String(),
					NodePort:   p.NodePort,
					Protocol:   string(p.Protocol),
				})
			}
			resources.Services = append(resources.Services, ServiceDetail{
				Name:        svc.Name,
				Namespace:   svc.Namespace,
				Labels:      svc.Labels,
				Type:        string(svc.Spec.Type),
				ClusterIP:   svc.Spec.ClusterIP,
				ExternalIPs: svc.Spec.ExternalIPs,
				Ports:       ports,
				Age:         svc.CreationTimestamp.UTC().Format(time.RFC3339),
			})
			resources.Summary.TotalServices++
		}
	}

	// ── Ingresses ────────────────────────────────────────────────────────────
	ingList, err := k8s.NetworkingV1().Ingresses("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list ingresses: %v", err)
	} else {
		for _, ing := range ingList.Items {
			ingDetail := buildIngressDetail(ing)
			resources.Ingresses = append(resources.Ingresses, ingDetail)
		}
	}

	// ── HPAs ─────────────────────────────────────────────────────────────────
	hpaList, err := k8s.AutoscalingV2().HorizontalPodAutoscalers("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list HPAs: %v", err)
	} else {
		for _, hpa := range hpaList.Items {
			resources.HPAs = append(resources.HPAs, buildHPADetail(hpa))
		}
	}

	// ── PVCs ─────────────────────────────────────────────────────────────────
	pvcList, err := k8s.CoreV1().PersistentVolumeClaims("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list PVCs: %v", err)
	} else {
		for _, pvc := range pvcList.Items {
			capacity := ""
			if qty, ok := pvc.Spec.Resources.Requests[corev1.ResourceStorage]; ok {
				capacity = qty.String()
			}
			modes := make([]string, 0, len(pvc.Spec.AccessModes))
			for _, m := range pvc.Spec.AccessModes {
				modes = append(modes, string(m))
			}
			sc := ""
			if pvc.Spec.StorageClassName != nil {
				sc = *pvc.Spec.StorageClassName
			}
			resources.PVCs = append(resources.PVCs, PVCDetail{
				Name:             pvc.Name,
				Namespace:        pvc.Namespace,
				Labels:           pvc.Labels,
				Status:           string(pvc.Status.Phase),
				VolumeName:       pvc.Spec.VolumeName,
				StorageClassName: sc,
				AccessModes:      modes,
				Capacity:         capacity,
				Age:              pvc.CreationTimestamp.UTC().Format(time.RFC3339),
			})
			resources.Summary.TotalPVCs++
		}
	}

	// ── Jobs ─────────────────────────────────────────────────────────────────
	jobList, err := k8s.BatchV1().Jobs("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list jobs: %v", err)
	} else {
		for _, j := range jobList.Items {
			startTime := ""
			if j.Status.StartTime != nil {
				startTime = j.Status.StartTime.UTC().Format(time.RFC3339)
			}
			completionTime := ""
			if j.Status.CompletionTime != nil {
				completionTime = j.Status.CompletionTime.UTC().Format(time.RFC3339)
			}
			completions := int32(0)
			if j.Spec.Completions != nil {
				completions = *j.Spec.Completions
			}
			resources.Jobs = append(resources.Jobs, JobDetail{
				Name:           j.Name,
				Namespace:      j.Namespace,
				Labels:         j.Labels,
				Completions:    completions,
				Active:         j.Status.Active,
				Succeeded:      j.Status.Succeeded,
				Failed:         j.Status.Failed,
				StartTime:      startTime,
				CompletionTime: completionTime,
				Age:            j.CreationTimestamp.UTC().Format(time.RFC3339),
			})
		}
	}

	// ── CronJobs ─────────────────────────────────────────────────────────────
	cjList, err := k8s.BatchV1().CronJobs("").List(ctx, metav1.ListOptions{})
	if err != nil {
		log.Printf("WARN: k8s-resources list cronjobs: %v", err)
	} else {
		for _, cj := range cjList.Items {
			lastSchedule := ""
			if cj.Status.LastScheduleTime != nil {
				lastSchedule = cj.Status.LastScheduleTime.UTC().Format(time.RFC3339)
			}
			suspend := false
			if cj.Spec.Suspend != nil {
				suspend = *cj.Spec.Suspend
			}
			resources.CronJobs = append(resources.CronJobs, CronJobDetail{
				Name:             cj.Name,
				Namespace:        cj.Namespace,
				Schedule:         cj.Spec.Schedule,
				Suspend:          suspend,
				LastScheduleTime: lastSchedule,
				ActiveJobs:       len(cj.Status.Active),
				Age:              cj.CreationTimestamp.UTC().Format(time.RFC3339),
			})
		}
	}

	// ── Warning Events (recent, last 30 min) ─────────────────────────────────
	eventList, err := k8s.CoreV1().Events("").List(ctx, metav1.ListOptions{
		FieldSelector: "type=Warning",
	})
	if err != nil {
		log.Printf("WARN: k8s-resources list events: %v", err)
	} else {
		cutoff := time.Now().Add(-30 * time.Minute)
		count := 0
		for _, e := range eventList.Items {
			if e.LastTimestamp.Time.Before(cutoff) {
				continue
			}
			resources.Events = append(resources.Events, EventDetail{
				Name:           e.Name,
				Namespace:      e.Namespace,
				Reason:         e.Reason,
				Message:        e.Message,
				Type:           e.Type,
				Count:          e.Count,
				InvolvedObject: e.InvolvedObject.Name,
				ObjectKind:     e.InvolvedObject.Kind,
				FirstTime:      safeFormatTime(e.FirstTimestamp.Time),
				LastTime:       safeFormatTime(e.LastTimestamp.Time),
			})
			count++
			if count >= 200 {
				break
			}
		}
	}

	// ── Node Metrics (metrics-server) ─────────────────────────────────────────
	if mc != nil {
		nodeMetricsList, err := mc.MetricsV1beta1().NodeMetricses().List(ctx, metav1.ListOptions{})
		if err != nil {
			log.Printf("WARN: k8s-resources node metrics: %v", err)
		} else {
			for _, nm := range nodeMetricsList.Items {
				cpuMillis := nm.Usage.Cpu().MilliValue()
				memBytes := nm.Usage.Memory().Value()
				cpuCap := nodeCPUCapMillis[nm.Name]
				memCap := nodeMemCapBytes[nm.Name]
				cpuPct := 0.0
				memPct := 0.0
				if cpuCap > 0 {
					cpuPct = float64(cpuMillis) / float64(cpuCap) * 100
				}
				if memCap > 0 {
					memPct = float64(memBytes) / float64(memCap) * 100
				}
				resources.NodeMetrics = append(resources.NodeMetrics, NodeMetricDetail{
					NodeName:      nm.Name,
					CPUUsage:      nm.Usage.Cpu().String(),
					MemoryUsage:   nm.Usage.Memory().String(),
					CPUPercent:    roundTo2(cpuPct),
					MemoryPercent: roundTo2(memPct),
				})
			}
		}

		// ── Pod Metrics (metrics-server) — top pods only ──────────────────────
		podMetricsList, err := mc.MetricsV1beta1().PodMetricses("").List(ctx, metav1.ListOptions{})
		if err != nil {
			log.Printf("WARN: k8s-resources pod metrics: %v", err)
		} else {
			for _, pm := range podMetricsList.Items {
				containers := make([]ContainerUsage, 0, len(pm.Containers))
				for _, c := range pm.Containers {
					containers = append(containers, ContainerUsage{
						Name:        c.Name,
						CPUUsage:    c.Usage.Cpu().String(),
						MemoryUsage: c.Usage.Memory().String(),
					})
				}
				resources.PodMetrics = append(resources.PodMetrics, PodMetricDetail{
					PodName:    pm.Name,
					Namespace:  pm.Namespace,
					Containers: containers,
				})
			}
		}
	}

	return DataBatch{
		Collector:   "k8s-resources",
		Items:       []K8sResources{resources},
		Count:       1,
		CollectedAt: time.Now().UTC(),
	}, nil
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func buildNodeDetail(n corev1.Node) NodeDetail {
	lbl := n.Labels
	roles := []string{}
	for k := range lbl {
		if len(k) > len("node-role.kubernetes.io/") && k[:len("node-role.kubernetes.io/")] == "node-role.kubernetes.io/" {
			roles = append(roles, k[len("node-role.kubernetes.io/"):])
		}
	}
	if len(roles) == 0 {
		roles = append(roles, "worker")
	}

	capacityType := "on-demand"
	if isSpotNode(n) {
		capacityType = "spot"
	}

	taints := make([]NodeTaint, 0, len(n.Spec.Taints))
	for _, t := range n.Spec.Taints {
		taints = append(taints, NodeTaint{
			Key:    t.Key,
			Value:  t.Value,
			Effect: string(t.Effect),
		})
	}

	conditions := make([]NodeCondition, 0, len(n.Status.Conditions))
	for _, c := range n.Status.Conditions {
		conditions = append(conditions, NodeCondition{
			Type:    string(c.Type),
			Status:  string(c.Status),
			Message: c.Message,
			Reason:  c.Reason,
		})
	}

	return NodeDetail{
		Name:              n.Name,
		Labels:            n.Labels,
		Roles:             roles,
		InstanceType:      lbl["node.kubernetes.io/instance-type"],
		CapacityType:      capacityType,
		Zone:              lbl["topology.kubernetes.io/zone"],
		Region:            lbl["topology.kubernetes.io/region"],
		OperatingSystem:   n.Status.NodeInfo.OperatingSystem,
		KernelVersion:     n.Status.NodeInfo.KernelVersion,
		ContainerRuntime:  n.Status.NodeInfo.ContainerRuntimeVersion,
		KubeletVersion:    n.Status.NodeInfo.KubeletVersion,
		CapacityCPU:       n.Status.Capacity.Cpu().String(),
		CapacityMemory:    n.Status.Capacity.Memory().String(),
		CapacityPods:      n.Status.Capacity.Pods().String(),
		AllocatableCPU:    n.Status.Allocatable.Cpu().String(),
		AllocatableMemory: n.Status.Allocatable.Memory().String(),
		AllocatablePods:   n.Status.Allocatable.Pods().String(),
		Conditions:        conditions,
		Taints:            taints,
		Unschedulable:     n.Spec.Unschedulable,
		Age:               n.CreationTimestamp.UTC().Format(time.RFC3339),
	}
}

func buildPodDetail(p corev1.Pod) PodDetail {
	containers := make([]ContainerDetail, 0, len(p.Spec.Containers))
	for _, cs := range p.Status.ContainerStatuses {
		spec := findContainer(p.Spec.Containers, cs.Name)
		state, waitReason := containerState(cs)
		cd := ContainerDetail{
			Name:         cs.Name,
			Image:        cs.Image,
			Ready:        cs.Ready,
			RestartCount: cs.RestartCount,
			State:        state,
			WaitingReason: waitReason,
		}
		if spec != nil {
			cd.CPURequest = quantityStr(spec.Resources.Requests, corev1.ResourceCPU)
			cd.CPULimit = quantityStr(spec.Resources.Limits, corev1.ResourceCPU)
			cd.MemoryRequest = quantityStr(spec.Resources.Requests, corev1.ResourceMemory)
			cd.MemoryLimit = quantityStr(spec.Resources.Limits, corev1.ResourceMemory)
			ports := make([]ContainerPort, 0, len(spec.Ports))
			for _, port := range spec.Ports {
				ports = append(ports, ContainerPort{
					Name:          port.Name,
					ContainerPort: port.ContainerPort,
					Protocol:      string(port.Protocol),
				})
			}
			cd.Ports = ports
		}
		containers = append(containers, cd)
	}
	// For pods without container statuses yet (e.g. Pending), use spec
	if len(containers) == 0 {
		for _, c := range p.Spec.Containers {
			ports := make([]ContainerPort, 0, len(c.Ports))
			for _, port := range c.Ports {
				ports = append(ports, ContainerPort{
					Name:          port.Name,
					ContainerPort: port.ContainerPort,
					Protocol:      string(port.Protocol),
				})
			}
			containers = append(containers, ContainerDetail{
				Name:          c.Name,
				Image:         c.Image,
				State:         "waiting",
				CPURequest:    quantityStr(c.Resources.Requests, corev1.ResourceCPU),
				CPULimit:      quantityStr(c.Resources.Limits, corev1.ResourceCPU),
				MemoryRequest: quantityStr(c.Resources.Requests, corev1.ResourceMemory),
				MemoryLimit:   quantityStr(c.Resources.Limits, corev1.ResourceMemory),
				Ports:         ports,
			})
		}
	}

	controllerKind, controllerName := podController(p)
	unschedulable := isPodUnschedulable(p)

	startedAt := ""
	if p.Status.StartTime != nil {
		startedAt = p.Status.StartTime.UTC().Format(time.RFC3339)
	}

	return PodDetail{
		Name:           p.Name,
		Namespace:      p.Namespace,
		NodeName:       p.Spec.NodeName,
		Labels:         p.Labels,
		Phase:          string(p.Status.Phase),
		QosClass:       string(p.Status.QOSClass),
		Reason:         p.Status.Reason,
		Message:        p.Status.Message,
		ControllerKind: controllerKind,
		ControllerName: controllerName,
		PodIP:          p.Status.PodIP,
		StartedAt:      startedAt,
		Age:            p.CreationTimestamp.UTC().Format(time.RFC3339),
		Containers:     containers,
		Unschedulable:  unschedulable,
	}
}

func buildIngressDetail(ing networkingv1.Ingress) IngressDetail {
	rules := make([]IngressRule, 0, len(ing.Spec.Rules))
	for _, r := range ing.Spec.Rules {
		paths := []IngressPath{}
		if r.HTTP != nil {
			for _, p := range r.HTTP.Paths {
				pt := ""
				if p.PathType != nil {
					pt = string(*p.PathType)
				}
				sp := ""
				if p.Backend.Service != nil {
					sp = fmt.Sprintf("%d", p.Backend.Service.Port.Number)
				}
				paths = append(paths, IngressPath{
					Path:        p.Path,
					PathType:    pt,
					ServiceName: func() string {
						if p.Backend.Service != nil {
							return p.Backend.Service.Name
						}
						return ""
					}(),
					ServicePort: sp,
				})
			}
		}
		rules = append(rules, IngressRule{
			Host:  r.Host,
			Paths: paths,
		})
	}

	className := ""
	if ing.Spec.IngressClassName != nil {
		className = *ing.Spec.IngressClassName
	}

	return IngressDetail{
		Name:      ing.Name,
		Namespace: ing.Namespace,
		Labels:    ing.Labels,
		ClassName: className,
		Rules:     rules,
		HasTLS:    len(ing.Spec.TLS) > 0,
		Age:       ing.CreationTimestamp.UTC().Format(time.RFC3339),
	}
}

func buildHPADetail(hpa autoscalingv2.HorizontalPodAutoscaler) HPADetail {
	minReplicas := int32(1)
	if hpa.Spec.MinReplicas != nil {
		minReplicas = *hpa.Spec.MinReplicas
	}

	cpuTarget := int32(0)
	for _, m := range hpa.Spec.Metrics {
		if m.Type == autoscalingv2.ResourceMetricSourceType &&
			m.Resource != nil &&
			m.Resource.Name == corev1.ResourceCPU &&
			m.Resource.Target.AverageUtilization != nil {
			cpuTarget = *m.Resource.Target.AverageUtilization
			break
		}
	}

	cpuCurrent := int32(0)
	for _, m := range hpa.Status.CurrentMetrics {
		if m.Type == autoscalingv2.ResourceMetricSourceType &&
			m.Resource != nil &&
			m.Resource.Name == corev1.ResourceCPU &&
			m.Resource.Current.AverageUtilization != nil {
			cpuCurrent = *m.Resource.Current.AverageUtilization
			break
		}
	}

	conditions := make([]HPACondition, 0, len(hpa.Status.Conditions))
	for _, c := range hpa.Status.Conditions {
		conditions = append(conditions, HPACondition{
			Type:    string(c.Type),
			Status:  string(c.Status),
			Message: c.Message,
			Reason:  c.Reason,
		})
	}

	return HPADetail{
		Name:            hpa.Name,
		Namespace:       hpa.Namespace,
		Labels:          hpa.Labels,
		TargetKind:      hpa.Spec.ScaleTargetRef.Kind,
		TargetName:      hpa.Spec.ScaleTargetRef.Name,
		MinReplicas:     minReplicas,
		MaxReplicas:     hpa.Spec.MaxReplicas,
		CurrentReplicas: hpa.Status.CurrentReplicas,
		DesiredReplicas: hpa.Status.DesiredReplicas,
		CPUTargetPct:    cpuTarget,
		CurrentCPUPct:   cpuCurrent,
		Age:             hpa.CreationTimestamp.UTC().Format(time.RFC3339),
		Conditions:      conditions,
	}
}

// ── Pod/container helper functions ───────────────────────────────────────────

func findContainer(containers []corev1.Container, name string) *corev1.Container {
	for i := range containers {
		if containers[i].Name == name {
			return &containers[i]
		}
	}
	return nil
}

func containerState(cs corev1.ContainerStatus) (state string, waitReason string) {
	if cs.State.Running != nil {
		return "running", ""
	}
	if cs.State.Waiting != nil {
		return "waiting", cs.State.Waiting.Reason
	}
	if cs.State.Terminated != nil {
		return "terminated", cs.State.Terminated.Reason
	}
	return "unknown", ""
}

func quantityStr(res corev1.ResourceList, r corev1.ResourceName) string {
	if res == nil {
		return ""
	}
	if q, ok := res[r]; ok {
		return q.String()
	}
	return ""
}

func podController(p corev1.Pod) (kind, name string) {
	for _, ref := range p.OwnerReferences {
		if ref.Controller != nil && *ref.Controller {
			return ref.Kind, ref.Name
		}
	}
	return "", ""
}

func isPodUnschedulable(p corev1.Pod) bool {
	for _, cond := range p.Status.Conditions {
		if cond.Type == corev1.PodScheduled &&
			cond.Status == corev1.ConditionFalse &&
			cond.Reason == corev1.PodReasonUnschedulable {
			return true
		}
	}
	return false
}

func isNodeReady(n corev1.Node) bool {
	for _, c := range n.Status.Conditions {
		if c.Type == corev1.NodeReady {
			return c.Status == corev1.ConditionTrue
		}
	}
	return false
}

func isSpotNode(n corev1.Node) bool {
	lbl := n.Labels
	return lbl["node-lifecycle"] == "spot" ||
		lbl["eks.amazonaws.com/capacityType"] == "SPOT" ||
		lbl["karpenter.sh/capacity-type"] == "spot" ||
		lbl["cloud.google.com/gke-spot"] == "true" ||
		lbl["kubernetes.azure.com/scalesetpriority"] == "spot"
}

func collectImages(containers []corev1.Container) []string {
	images := make([]string, 0, len(containers))
	for _, c := range containers {
		images = append(images, c.Image)
	}
	return images
}

func derefInt32(p *int32) int32 {
	if p == nil {
		return 0
	}
	return *p
}

func safeFormatTime(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.UTC().Format(time.RFC3339)
}

func roundTo2(f float64) float64 {
	return float64(int(f*100)) / 100
}

// batchv1 and networkingv1 imports are used — keep the compiler happy
var _ = batchv1.Job{}
var _ = networkingv1.Ingress{}
