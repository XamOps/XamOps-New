// Package gate decides, once per cycle, how far each controller may go.
//
// Every gate is an AND of the env flag (operator), the policy bundle (backend/UI) and
// the autoscaler mode; DRY_RUN then turns Live into Simulate. No single input can open
// a gate on its own — in particular a backend that leaves rebalancer.status=running
// cannot start the rebalancer while ENABLE_REBALANCER=false (the fail-open baseline
// at cmd/autoscaler/main.go:163). Truth tables: plan.md section 5.
//
// The Live mode is necessary, not sufficient: node actions additionally need a drain
// budget slot, a drainable plan and (rebalancer) a non-terminal operation.
package gate

import (
	"fmt"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/policy"
)

// Mode is how far a controller may go this cycle.
type Mode int

const (
	// Off — the controller does not run at all.
	Off Mode = iota
	// Analyse — read-only analysis/report, no action is attempted or simulated.
	Analyse
	// Simulate — the controller plans actions and reports them as simulated (DRY_RUN).
	Simulate
	// Live — the controller may mutate the cluster (subject to budget/plan/ledger).
	Live
)

func (m Mode) String() string {
	switch m {
	case Off:
		return "off"
	case Analyse:
		return "analyse"
	case Simulate:
		return "simulate"
	case Live:
		return "live"
	}
	return fmt.Sprintf("mode(%d)", int(m))
}

// MarshalText makes Mode serialize as its name.
func (m Mode) MarshalText() ([]byte, error) { return []byte(m.String()), nil }

// Decision is a gate outcome plus the reason (the first failing condition when Off).
type Decision struct {
	Mode   Mode   `json:"mode"`
	Reason string `json:"reason"`
}

func (d Decision) String() string { return fmt.Sprintf("%s (%s)", d.Mode, d.Reason) }

func off(format string, a ...interface{}) Decision {
	return Decision{Mode: Off, Reason: fmt.Sprintf(format, a...)}
}

// actOrSimulate returns Simulate under DRY_RUN, Live otherwise.
func actOrSimulate(cfg *config.Config) Decision {
	if cfg.DryRun {
		return Decision{Mode: Simulate, Reason: "DRY_RUN=true"}
	}
	return Decision{Mode: Live, Reason: "all gates open (DRY_RUN=false)"}
}

func modeDisabled(b policy.PolicyBundle) bool { return b.AutoscalerMode == "disabled" }

// fullOrScoped — workload/analysis paths run in full and scoped mode. Any other value
// (empty, unknown) fails closed.
func fullOrScoped(b policy.PolicyBundle) bool {
	return b.AutoscalerMode == "full" || b.AutoscalerMode == "scoped"
}

// Rebalancer: ENABLE_REBALANCER ∧ mode=full ∧ rebalancer.enabled ∧ status=running.
func Rebalancer(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !cfg.EnableRebalancer:
		return off("ENABLE_REBALANCER=false")
	case b.AutoscalerMode != "full":
		return off("autoscalerMode=%q (rebalancer needs full)", b.AutoscalerMode)
	case !b.Rebalancer.Enabled:
		return off("rebalancer.enabled=false")
	case b.Rebalancer.Status != "running":
		return off("rebalancer.status=%q (not running)", b.Rebalancer.Status)
	}
	return actOrSimulate(cfg)
}

// Evictor: ENABLE_EVICTOR ∧ evictorPolicy.enabled ∧ mode=full; analyseOnly → Analyse.
func Evictor(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !cfg.EnableEvictor:
		return off("ENABLE_EVICTOR=false")
	case !b.EvictorPolicy.Enabled:
		return off("evictorPolicy.enabled=false")
	case b.AutoscalerMode != "full":
		return off("autoscalerMode=%q (evictor needs full)", b.AutoscalerMode)
	case b.EvictorPolicy.AnalyseOnly:
		return Decision{Mode: Analyse, Reason: "evictorPolicy.analyseOnly=true"}
	}
	return actOrSimulate(cfg)
}

// NodeOptimizerAnalysis: ENABLE_NODE_OPTIMIZER ∧ nodeOptimizerPolicy.enabled ∧ mode∈{full,scoped}.
func NodeOptimizerAnalysis(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !cfg.EnableNodeOptimizer:
		return off("ENABLE_NODE_OPTIMIZER=false")
	case !b.NodeOptimizerPolicy.Enabled:
		return off("nodeOptimizerPolicy.enabled=false")
	case !fullOrScoped(b):
		return off("autoscalerMode=%q (node optimizer needs full or scoped)", b.AutoscalerMode)
	}
	return Decision{Mode: Analyse, Reason: "analysis only"}
}

// NodeAction gates one node-optimizer action (cordon, idle drain, spot ratio,
// interruption handling, Karpenter patch): analysis on ∧ mode=full ∧ the action's
// policy flag. actionName names the flag in the reason.
func NodeAction(cfg *config.Config, b policy.PolicyBundle, actionFlag bool, actionName string) Decision {
	if a := NodeOptimizerAnalysis(cfg, b); a.Mode == Off {
		return a
	}
	switch {
	case b.AutoscalerMode != "full":
		return off("autoscalerMode=%q (node actions need full)", b.AutoscalerMode)
	case !actionFlag:
		return off("nodeOptimizerPolicy.%s not set", actionName)
	}
	return actOrSimulate(cfg)
}

// Interruption gates spot-interruption drains on nodeOptimizerPolicy.handleSpotInterruptions.
// The backend serves handleSpotInterruptions=false unless readiness
// spot-interruption-actions is true, and a served policy that omits the field (or the
// whole section) decodes to the DefaultBundle value false, so the gate fails closed.
func Interruption(cfg *config.Config, b policy.PolicyBundle) Decision {
	return NodeAction(cfg, b, b.NodeOptimizerPolicy.HandleSpotInterruptions, "handleSpotInterruptions")
}

// RightsizerAnalysis: ENABLE_RIGHTSIZER ∧ workloadRightsizerPolicy.enabled ∧ mode∈{full,scoped}.
func RightsizerAnalysis(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !cfg.EnableRightsizer:
		return off("ENABLE_RIGHTSIZER=false")
	case !b.WorkloadRightsizerPolicy.Enabled:
		return off("workloadRightsizerPolicy.enabled=false")
	case !fullOrScoped(b):
		return off("autoscalerMode=%q (rightsizer needs full or scoped)", b.AutoscalerMode)
	}
	return Decision{Mode: Analyse, Reason: "analysis"}
}

// RightsizerApply: analysis on ∧ ENABLE_RIGHTSIZER_APPLY ∧ applyMode=immediate.
func RightsizerApply(cfg *config.Config, b policy.PolicyBundle) Decision {
	if a := RightsizerAnalysis(cfg, b); a.Mode == Off {
		return a
	}
	switch {
	case !cfg.EnableRightsizerApply:
		return off("ENABLE_RIGHTSIZER_APPLY=false")
	case b.WorkloadRightsizerPolicy.ApplyMode != "immediate":
		return off("workloadRightsizerPolicy.applyMode=%q (apply needs immediate)", b.WorkloadRightsizerPolicy.ApplyMode)
	}
	return actOrSimulate(cfg)
}

// HPAManager: ENABLE_HPA_MANAGER ∧ hpaManagerPolicy.enabled ∧ mode∈{full,scoped}.
// The HPA manager is analysis-only: it is never Live.
func HPAManager(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !cfg.EnableHPAManager:
		return off("ENABLE_HPA_MANAGER=false")
	case !b.HPAManagerPolicy.Enabled:
		return off("hpaManagerPolicy.enabled=false")
	case !fullOrScoped(b):
		return off("autoscalerMode=%q (HPA manager needs full or scoped)", b.AutoscalerMode)
	}
	return Decision{Mode: Analyse, Reason: "analysis only (the HPA manager never mutates)"}
}

// VPAManager: ENABLE_VPA_MANAGER ∧ vpaManagerPolicy.enabled ∧ mode∈{full,scoped}.
//   - analyseOnly=true OR updateMode∈{"","Off","Initial"} → Analyse (collect only; never mutates)
//   - an acting updateMode                                → actOrSimulate(cfg) (Simulate under DRY_RUN)
//
// A Simulate/Live decision only authorises the controller to act; the controller
// (controller.ApplyInPlace) applies ONLY in place via the pods/resize subresource and
// REFUSES any change that would require a restart — it never evicts, deletes or
// template-patches a workload. VPAManager.Apply (the VPA-CRD writer) stays callerless.
func VPAManager(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !cfg.EnableVPAManager:
		return off("ENABLE_VPA_MANAGER=false")
	case !b.VPAManagerPolicy.Enabled:
		return off("vpaManagerPolicy.enabled=false")
	case !fullOrScoped(b):
		return off("autoscalerMode=%q (VPA manager needs full or scoped)", b.AutoscalerMode)
	case b.VPAManagerPolicy.AnalyseOnly || !isActingUpdateMode(b.VPAManagerPolicy.UpdateMode):
		return Decision{Mode: Analyse, Reason: "analysis only (analyseOnly or non-acting updateMode)"}
	}
	return actOrSimulate(cfg)
}

// isActingUpdateMode reports whether the mode means XamOps should apply the
// recommendation to a RUNNING pod in place. "" / "Off" never touch a pod; "Initial"
// only affects pod CREATION (XamOps applies nothing in place for it). The string set
// MUST stay byte-identical to the backend ACTING_UPDATE_MODES.
func isActingUpdateMode(mode string) bool {
	switch mode {
	case "Recreate", "Auto", "InPlaceOrRecreate":
		return true
	}
	return false
}

// ConsolidationActions: ENABLE_CONSOLIDATION ∧ consolidationPolicy.enabled ∧
// mode=full; DRY_RUN → Simulate. This is the acting gate for the consolidation
// controller (EMPTY/DRAIN + the controller-owned ASG terminate), mirroring
// gate.Evictor. It fails closed on anything other than full mode. The look-only
// ConsolidationAnalysis gate below is unchanged and keeps computing the plan.
func ConsolidationActions(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !cfg.EnableConsolidation:
		return off("ENABLE_CONSOLIDATION=false")
	case b.AutoscalerMode != "full":
		return off("autoscalerMode=%q (consolidation actions need full)", b.AutoscalerMode)
	case !b.ConsolidationEnabled():
		return off("consolidationPolicy.enabled=false")
	}
	return actOrSimulate(cfg)
}

// ConsolidationAnalysis: mode∈{full,scoped}. Consolidation Phase 1 is look-only: it
// is never Simulate or Live, and no env flag opens it (every ENABLE_* stays false
// in the manifest, so gating on one would mean no plan is ever computed). The
// package it gates only lists objects.
func ConsolidationAnalysis(cfg *config.Config, b policy.PolicyBundle) Decision {
	switch {
	case modeDisabled(b):
		return off("autoscalerMode=disabled")
	case !fullOrScoped(b):
		return off("autoscalerMode=%q (consolidation analysis needs full or scoped)", b.AutoscalerMode)
	}
	return Decision{Mode: Analyse, Reason: "look-only: consolidation never acts"}
}
