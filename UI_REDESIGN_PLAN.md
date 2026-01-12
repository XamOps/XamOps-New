# XamOps EKS Dashboard - Modern UI Redesign Plan

## Executive Summary

This document outlines a comprehensive UI redesign for the XamOps EKS Dashboard, inspired by Cast.ai's modern interface while maintaining all existing functionality. The redesign focuses on:

1. **Visual Hierarchy** - Critical information first
2. **Data Storytelling** - Making metrics meaningful to customers
3. **Action-Oriented Design** - Clear next steps for optimization
4. **Modern Aesthetics** - Clean, professional, enterprise-grade

---

## 🎨 Design Philosophy

### Core Principles
- **Simplicity First**: Reduce cognitive load with clear visual hierarchy
- **Insight Over Data**: Transform raw metrics into actionable insights
- **Progressive Disclosure**: Show overview first, details on demand
- **Consistent Language**: Use customer-friendly terminology

### Color Psychology
- **Red (#dc2626)**: Critical issues requiring immediate action
- **Orange (#f59e0b)**: Warnings and optimization opportunities
- **Green (#16a34a)**: Healthy status and positive trends
- **Blue (#1e88e5)**: Informational and neutral states
- **Purple (#8b5cf6)**: Premium features and AI insights

---

## 📊 Redesigned Layout Structure

### 1. Hero Section - Cluster At-A-Glance
**Current**: Basic cluster name with badges
**Redesigned**: Executive summary card with instant insights

```
┌─────────────────────────────────────────────────────────────────┐
│  🟢 eks-production-us-east-1                    Last sync: 2m ago│
│  v1.28 | us-east-1 | 12 nodes | 156 pods                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ COST TODAY  │  │ EFFICIENCY  │  │ SECURITY    │             │
│  │             │  │             │  │             │             │
│  │  $127.45    │  │    68%      │  │  12 Issues  │             │
│  │  ↑ 5% vs    │  │  Medium     │  │  3 Critical │             │
│  │  yesterday  │  │  Save $41/d │  │  View →     │             │
│  └─────────────┘  └─────────────┘  └─────────────┘             │
│                                                                  │
│  💡 Optimization Tip: 8 nodes are underutilized. Consider       │
│     enabling Karpenter autoscaling to save ~$35/day.            │
└─────────────────────────────────────────────────────────────────┘
```

**Key Changes**:
- Add cost and efficiency prominently
- Show optimization opportunities immediately
- Include actionable recommendations
- Time-based comparison (vs yesterday)

---

### 2. Navigation Tabs - Simplified & Focused

**Current**: Monitoring & Resources | Security & Compliance
**Redesigned**:

```
┌─────────────────────────────────────────────────────────────────┐
│ [Overview*] [Cost] [Performance] [Security] [Workloads]         │
└─────────────────────────────────────────────────────────────────┘
```

**Tab Distribution**:
- **Overview**: Health, quick wins, trends (NEW)
- **Cost**: Spend analysis, waste detection, forecasting
- **Performance**: CPU/Memory, node pressure, pod scheduling
- **Security**: Vulnerabilities, compliance, Falco alerts
- **Workloads**: Deployments, DaemonSets, Pods (detailed view)

---

## 🏗️ Tab-by-Tab Redesign

---

## TAB 1: OVERVIEW (NEW)

**Purpose**: Give customers instant understanding of cluster health and actionable next steps

### Section 1.1: Health Score Card (Inspired by Cast.ai)

```
┌─────────────────────────────────────────────────────────────────┐
│                    CLUSTER HEALTH SCORE                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│                           ╭───────╮                             │
│                           │       │                             │
│                           │  68   │  Medium Health              │
│                           │       │  ↓ 5 from last week        │
│                           ╰───────╯                             │
│                                                                  │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│   │ RESOURCE     │  │ COST         │  │ SECURITY     │        │
│   │ EFFICIENCY   │  │ OPTIMIZATION │  │ POSTURE      │        │
│   │              │  │              │  │              │        │
│   │    72/100    │  │    55/100    │  │    78/100    │        │
│   │    🟢 Good   │  │    🟡 Fair   │  │    🟢 Good   │        │
│   └──────────────┘  └──────────────┘  └──────────────┘        │
│                                                                  │
│   Components:                                                   │
│   • CPU Utilization: 65% ──────────────── 🟢 Optimal          │
│   • Memory Utilization: 58% ────────────── 🟢 Optimal          │
│   • Node Efficiency: 68% ─────────────────  🟡 Can Improve     │
│   • Cost/Resource Ratio: 55% ─────────────  🟡 Wasteful        │
│   • Security Events: 12 (3 Critical) ──────  🟡 Action Needed  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Calculation Logic** (Add to Alpine.js):
```javascript
getClusterHealthScore() {
  const cpu = this.dashboard.clusterOverview.cpuUsagePercent;
  const mem = this.dashboard.clusterOverview.memoryUsagePercent;
  const efficiency = this.getCostEfficiency();
  const criticalAlerts = this.dashboard.securityMetrics?.eventsByPriority?.['4'] || 0;

  // Resource efficiency score (0-40 points)
  let resourceScore = 0;
  const avgUtil = (cpu + mem) / 2;
  if (avgUtil >= 40 && avgUtil <= 75) resourceScore = 40; // Optimal
  else if (avgUtil < 40) resourceScore = Math.max(0, 40 - (40 - avgUtil)); // Underutilized
  else resourceScore = Math.max(0, 40 - (avgUtil - 75)); // Overutilized

  // Cost optimization score (0-30 points)
  const costScore = Math.min(30, (efficiency / 100) * 30);

  // Security score (0-30 points)
  let securityScore = 30;
  securityScore -= Math.min(30, criticalAlerts * 5); // -5 per critical alert
  securityScore -= Math.min(10, (this.dashboard.kubescape?.totalCritical || 0));

  return Math.round(resourceScore + costScore + Math.max(0, securityScore));
}
```

### Section 1.2: Quick Wins (Action Panel)

```
┌─────────────────────────────────────────────────────────────────┐
│  🎯 RECOMMENDED ACTIONS TO IMPROVE YOUR CLUSTER                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 🔴 Fix 3 Critical Security Vulnerabilities                  │
│     Impact: Prevent potential exploits                          │
│     [View Details] [Auto-Fix Available]                         │
│                                                                  │
│  2. 💰 Enable Karpenter Autoscaling                             │
│     Impact: Save ~$35/day with intelligent node scaling         │
│     [Configure Now] [Learn More]                                │
│                                                                  │
│  3. ⚡ Optimize 8 Underutilized Nodes                           │
│     Impact: Reduce waste by 28% ($41/day savings)               │
│     [View Nodes] [Apply Recommendations]                        │
│                                                                  │
│  4. 🛡️ 15 Images Need Security Scanning                         │
│     Impact: Reduce vulnerability exposure                       │
│     [Scan Now] [Schedule Scans]                                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Data Sources**:
- Security: `dashboard.kubescape.totalCritical`, `dashboard.trivy.summary.totalCritical`
- Cost: Calculate from `dashboard.costMetrics.nodeCosts` where `cpuUsagePercent < 40`
- Autoscaling: Check Karpenter status API

### Section 1.3: Trends Over Time (24h)

```
┌─────────────────────────────────────────────────────────────────┐
│  📈 CLUSTER METRICS - LAST 24 HOURS                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  CPU Usage (%)                    Memory Usage (%)              │
│  ┌────────────────────┐          ┌────────────────────┐        │
│  │     ╱╲    ╱╲       │          │         ╱╲         │        │
│  │    ╱  ╲  ╱  ╲      │          │        ╱  ╲        │        │
│  │   ╱    ╲╱    ╲─    │          │     ──╱    ╲──     │        │
│  │  ╱                 │          │   ╱           ╲    │        │
│  └────────────────────┘          └────────────────────┘        │
│  Current: 65%  Avg: 62%          Current: 58%  Avg: 55%        │
│                                                                  │
│  Cost ($)                         Security Events               │
│  ┌────────────────────┐          ┌────────────────────┐        │
│  │         ─────────  │          │    │  │             │        │
│  │      ───           │          │  │ │  │ │           │        │
│  │   ───              │          │  │ │  │ │  │        │        │
│  │ ──                 │          │  │ │  │ │  │  │     │        │
│  └────────────────────┘          └────────────────────┘        │
│  Today: $127  Avg: $121          Today: 12  Avg: 8             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation Note**: Use Chart.js or ApexCharts for sparkline visualizations
- Store historical data in localStorage or fetch from backend
- Show mini-charts with key trend indicators

---

## TAB 2: COST (Redesigned)

### Section 2.1: Cost Dashboard (Cast.ai Inspired)

```
┌─────────────────────────────────────────────────────────────────┐
│  💰 COST OVERVIEW                                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────┐ │
│  │ TODAY'S SPEND    │  │ MONTHLY FORECAST │  │ POTENTIAL     │ │
│  │                  │  │                  │  │ SAVINGS       │ │
│  │  $127.45         │  │   $3,823.50      │  │               │ │
│  │  ↑ $5.20 vs      │  │   Based on avg   │  │  $1,045/mo    │ │
│  │  yesterday       │  │   daily trend    │  │  with actions │ │
│  └──────────────────┘  └──────────────────┘  └───────────────┘ │
│                                                                  │
│  Cost Efficiency: 68%  [━━━━━━━━━━━━━━━─────────] Medium       │
│                                                                  │
│  ⚠️ You're spending 32% on idle resources ($41/day)             │
│     [View Waste Report] [Get Optimization Plan]                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Section 2.2: Cost Breakdown - Hierarchical View

**Current**: Simple table
**Redesigned**: Visual hierarchy with drill-down

```
┌─────────────────────────────────────────────────────────────────┐
│  📊 COST BREAKDOWN BY RESOURCE                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Compute (Nodes)            $98.40  77%  [▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓───] │
│    ├─ m5.2xlarge (4x)       $64.32  50%  [▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓─────] │
│    ├─ m5.xlarge (6x)        $28.92  23%  [▓▓▓▓▓▓▓▓▓───────────] │
│    └─ t3.large (2x)         $5.16   4%   [▓▓────────────────] │
│                                                                  │
│  Storage (EBS)              $18.75  15%  [▓▓▓▓▓▓▓───────────] │
│    ├─ gp3 volumes           $12.60  10%  [▓▓▓▓▓─────────────] │
│    └─ gp2 volumes           $6.15   5%   [▓▓▓───────────────] │
│                                                                  │
│  Load Balancers             $7.35   6%   [▓▓▓────────────────] │
│                                                                  │
│  Data Transfer              $2.95   2%   [▓──────────────────] │
│                                                                  │
│  Total Daily Cost:  $127.45                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Section 2.3: Node Cost Matrix (Heat Map)

```
┌─────────────────────────────────────────────────────────────────┐
│  🔥 NODE EFFICIENCY HEATMAP                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Node Name            Type        $/hr  Usage   Efficiency      │
│  ─────────────────────────────────────────────────────────────  │
│  ip-10-0-1-45         m5.2xlarge  $0.38  🔴 28%  ⚠️ Wasteful    │
│  ip-10-0-1-78         m5.2xlarge  $0.38  🔴 31%  ⚠️ Wasteful    │
│  ip-10-0-2-12         m5.xlarge   $0.19  🟡 68%  ⚡ Good         │
│  ip-10-0-2-34         m5.xlarge   $0.19  🟢 82%  ✅ Optimal     │
│  ip-10-0-3-56         t3.large    $0.08  🟢 91%  ✅ Optimal     │
│  ip-10-0-3-78         t3.large    $0.08  🔴 22%  ⚠️ Wasteful    │
│                                                                  │
│  Legend:                                                         │
│  🟢 Optimal (70-85%) | 🟡 Good (50-70%) | 🔴 Wasteful (<50%)    │
│                                                                  │
│  [Export Report] [Schedule Right-Sizing] [Enable Auto-Scaling]  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Sort Options**:
- By waste (default)
- By cost
- By efficiency
- By instance type

---

## TAB 3: PERFORMANCE (Redesigned)

### Section 3.1: Resource Utilization Dashboard

```
┌─────────────────────────────────────────────────────────────────┐
│  ⚡ CLUSTER RESOURCE UTILIZATION                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────┐        ┌────────────────────┐          │
│  │   CPU USAGE        │        │   MEMORY USAGE     │          │
│  │                    │        │                    │          │
│  │       ╭─────╮      │        │       ╭─────╮      │          │
│  │       │     │      │        │       │     │      │          │
│  │       │ 65% │      │        │       │ 58% │      │          │
│  │       │     │      │        │       │     │      │          │
│  │       ╰─────╯      │        │       ╰─────╯      │          │
│  │                    │        │                    │          │
│  │   52 / 80 cores    │        │  186 / 320 GiB     │          │
│  │   🟢 Healthy       │        │  🟢 Healthy        │          │
│  │                    │        │                    │          │
│  │   Allocatable:     │        │   Allocatable:     │          │
│  │   75 cores (94%)   │        │   298 GiB (93%)    │          │
│  └────────────────────┘        └────────────────────┘          │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  RESOURCE PRESSURE                                      │   │
│  │                                                          │   │
│  │  CPU:    [▓▓▓▓▓▓▓▓▓▓▓▓▓────────]  65%  🟢 Normal       │   │
│  │  Memory: [▓▓▓▓▓▓▓▓▓▓▓▓──────────]  58%  🟢 Normal       │   │
│  │  Pods:   [▓▓▓▓▓▓▓▓▓▓▓▓▓▓────────]  71%  🟡 Moderate     │   │
│  │  Disk:   [▓▓▓▓▓▓▓▓▓───────────]   42%  🟢 Low          │   │
│  │                                                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Section 3.2: Node Health Matrix (Grid View)

**Current**: List of nodes with bars
**Redesigned**: Grid visualization with status indicators

```
┌─────────────────────────────────────────────────────────────────┐
│  🖥️  NODE HEALTH MATRIX                                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┏━━━━━━━━━━┓  ┏━━━━━━━━━━┓  ┏━━━━━━━━━━┓  ┏━━━━━━━━━━┓      │
│  ┃   🟢     ┃  ┃   🟢     ┃  ┃   🟡     ┃  ┃   🔴     ┃      │
│  ┃ Node-01  ┃  ┃ Node-02  ┃  ┃ Node-03  ┃  ┃ Node-04  ┃      │
│  ┃ m5.xlarge┃  ┃ m5.xlarge┃  ┃ m5.2xlrg ┃  ┃ m5.2xlrg ┃      │
│  ┃          ┃  ┃          ┃  ┃          ┃  ┃          ┃      │
│  ┃ CPU: 82% ┃  ┃ CPU: 75% ┃  ┃ CPU: 68% ┃  ┃ CPU: 28% ┃      │
│  ┃ MEM: 78% ┃  ┃ MEM: 71% ┃  ┃ MEM: 65% ┃  ┃ MEM: 31% ┃      │
│  ┃ Pods:18  ┃  ┃ Pods:16  ┃  ┃ Pods:24  ┃  ┃ Pods: 8  ┃      │
│  ┗━━━━━━━━━━┛  ┗━━━━━━━━━━┛  ┗━━━━━━━━━━┛  ┗━━━━━━━━━━┛      │
│                                                                  │
│  ┏━━━━━━━━━━┓  ┏━━━━━━━━━━┓  ┏━━━━━━━━━━┓  ┏━━━━━━━━━━┓      │
│  ┃   🟢     ┃  ┃   🟡     ┃  ┃   🟢     ┃  ┃   🔴     ┃      │
│  ┃ Node-05  ┃  ┃ Node-06  ┃  ┃ Node-07  ┃  ┃ Node-08  ┃      │
│  ┃ t3.large ┃  ┃ m5.xlarge┃  ┃ t3.large ┃  ┃ m5.2xlrg ┃      │
│  ┃          ┃  ┃          ┃  ┃          ┃  ┃          ┃      │
│  ┃ CPU: 91% ┃  ┃ CPU: 68% ┃  ┃ CPU: 88% ┃  ┃ CPU: 31% ┃      │
│  ┃ MEM: 85% ┃  ┃ MEM: 62% ┃  ┃ MEM: 79% ┃  ┃ MEM: 29% ┃      │
│  ┃ Pods:12  ┃  ┃ Pods:14  ┃  ┃ Pods:11  ┃  ┃ Pods: 6  ┃      │
│  ┗━━━━━━━━━━┛  ┗━━━━━━━━━━┛  ┗━━━━━━━━━━┛  ┗━━━━━━━━━━┛      │
│                                                                  │
│  Status: 🟢 Optimal (4) | 🟡 Moderate (2) | 🔴 Underutilized(2)│
│                                                                  │
│  [Sort by Pressure] [Sort by Cost] [Sort by Pods]               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Click to expand**: Show detailed metrics modal

### Section 3.3: Pod Scheduling Efficiency

```
┌─────────────────────────────────────────────────────────────────┐
│  📦 POD SCHEDULING EFFICIENCY                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Total Pods: 156            Status Distribution:                │
│  Pods/Node Avg: 13          ┌─────────────────────────┐        │
│  Max Pods/Node: 110         │ 🟢 Running:     142     │        │
│                              │ 🟡 Pending:       8     │        │
│  Pod Distribution:           │ 🔵 Succeeded:    4     │        │
│                              │ 🔴 Failed:       2     │        │
│  ╔═══════════════════════╗   └─────────────────────────┘        │
│  ║▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░║  Node 1 (18 pods)  82% full         │
│  ╠═══════════════════════╣                                      │
│  ║▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░║  Node 2 (16 pods)  73% full         │
│  ╠═══════════════════════╣                                      │
│  ║▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓║  Node 3 (24 pods)  109% full ⚠️      │
│  ╠═══════════════════════╣                                      │
│  ║▓▓▓▓▓▓▓░░░░░░░░░░░░░░░║  Node 4 (8 pods)   36% full         │
│  ╠═══════════════════════╣                                      │
│  ║▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░║  Node 5 (12 pods)  55% full         │
│  ╚═══════════════════════╝                                      │
│                                                                  │
│  ⚠️ 8 pods pending due to resource constraints                  │
│  💡 Consider adding a node or enabling autoscaling              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## TAB 4: SECURITY (Redesigned - Cast.ai Inspired)

### Section 4.1: Security Overview Card

```
┌─────────────────────────────────────────────────────────────────┐
│  🛡️  SECURITY POSTURE                                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│                  ╭───────────────╮                              │
│                  │               │                              │
│                  │      78       │   Good Security Posture      │
│                  │               │   ↑ 5 from last scan        │
│                  ╰───────────────╯                              │
│                                                                  │
│  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐ │
│  │ VULNERABILITIES │  │ COMPLIANCE      │  │ RUNTIME ALERTS │ │
│  │                 │  │                 │  │                │ │
│  │   12 Total      │  │   75% Score     │  │   12 events    │ │
│  │   3 Critical 🔴 │  │   Passed: 3/4   │  │   3 Critical   │ │
│  │   3 High 🟠     │  │   Failed: 1/4   │  │   Last hour: 2 │ │
│  │   6 Medium 🟡   │  │   [View Report] │  │   [View Logs]  │ │
│  └─────────────────┘  └─────────────────┘  └────────────────┘ │
│                                                                  │
│  🚨 URGENT: 3 critical vulnerabilities need immediate patching  │
│     [View Details] [Auto-Remediation Available]                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Section 4.2: Vulnerability Management (Cast.ai Style)

```
┌─────────────────────────────────────────────────────────────────┐
│  🐛 VULNERABILITIES BY SEVERITY                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │  🔴 CRITICAL│  │  🟠 HIGH    │  │  🟡 MEDIUM  │            │
│  │             │  │             │  │             │            │
│  │      3      │  │      3      │  │      6      │            │
│  │             │  │             │  │             │            │
│  │  2 fixes    │  │  1 fixes    │  │  1 fixes    │            │
│  └─────────────┘  └─────────────┘  └─────────────┘            │
│                                                                  │
│  from 3 detected image repositories                             │
│                                                                  │
│  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓  │
│  ┃ MOST VULNERABLE IMAGE REPOSITORIES                        ┃  │
│  ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫  │
│  ┃                                                            ┃  │
│  ┃  k8s.gcr.io/cpvpa-amd64                                   ┃  │
│  ┃  Tag: v0.8.5                                              ┃  │
│  ┃  🔴 1  🟠 1  🟡 1  Fixes: 4                               ┃  │
│  ┃  [Scan Report →]                                          ┃  │
│  ┃                                                            ┃  │
│  ┃  us-docker.pkg.dev/castai-hub/library/agent               ┃  │
│  ┃  Tag: v0.28.0                                             ┃  │
│  ┃  🔴 1  🟠 1  🟡 1  Fixes: 4                               ┃  │
│  ┃  [Scan Report →]                                          ┃  │
│  ┃                                                            ┃  │
│  ┃  us-docker.pkg.dev/castai-hub/library/avictor             ┃  │
│  ┃  Tag: a24c4d52a0f7                                        ┃  │
│  ┃  🔴 1  🟠 1  🟡 1  Fixes: 6                               ┃  │
│  ┃  [Scan Report →]                                          ┃  │
│  ┃                                                            ┃  │
│  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛  │
│                                                                  │
│  [Report →]                                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Data Mapping**:
- From `dashboard.trivy.workloads[]`
- Group by `image` field
- Aggregate `critical`, `high`, `medium`, `low` counts
- Show top 3 most vulnerable images

### Section 4.3: Compliance Dashboard

```
┌─────────────────────────────────────────────────────────────────┐
│  ✅ COMPLIANCE WITH CAST AI                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│       ╭───────╮                                                 │
│       │       │                                                 │
│       │  75%  │    3/4 checks passed                           │
│       │       │                                                 │
│       ╰───────╯                                                 │
│                                                                  │
│  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓    │
│  ┃ NON-COMPLIANT RESOURCES                                ┃    │
│  ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫    │
│  ┃                                                         ┃    │
│  ┃  Nodes                                            30   ┃    │
│  ┃  [━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━]  ┃    │
│  ┃                                                         ┃    │
│  ┃  Workloads                                         3   ┃    │
│  ┃  [━━━━━━━━━━━━━]                                       ┃    │
│  ┃                                                         ┃    │
│  ┃  Other                                             0   ┃    │
│  ┃  [━]                                                    ┃    │
│  ┃                                                         ┃    │
│  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛    │
│                                                                  │
│  [Report →]                                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Data Source**: `dashboard.kubescape`

### Section 4.4: Falco Runtime Alerts

```
┌─────────────────────────────────────────────────────────────────┐
│  🚨 RUNTIME SECURITY ALERTS (FALCO)                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Last Hour: 12 events    Total: 247 events                      │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│  │ 🔴 CRITICAL  │  │ 🟡 WARNING   │  │ 🔵 INFO      │         │
│  │              │  │              │  │              │         │
│  │      3       │  │      5       │  │      4       │         │
│  └──────────────┘  └──────────────┘  └──────────────┘         │
│                                                                  │
│  Recent Critical Events:                                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ 🔴 Container with sensitive mount started                │   │
│  │    Pod: nginx-7d8b49557c-x9k2m | Namespace: production   │   │
│  │    Rule: Container drift detected                        │   │
│  │    2 minutes ago                                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ 🔴 Unauthorized process spawned                          │   │
│  │    Pod: api-server-6f8c7b9d-5hk3p | Namespace: default   │   │
│  │    Rule: Terminal shell in container                     │   │
│  │    8 minutes ago                                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
│  [View All Alerts] [Configure Rules] [Silence Alerts]           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Data Source**: `dashboard.securityMetrics.alerts[]`

---

## TAB 5: WORKLOADS (New Detailed View)

### Section 5.1: Workload Overview Cards

```
┌─────────────────────────────────────────────────────────────────┐
│  📦 WORKLOAD SUMMARY                                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐       │
│  │ DEPLOYMENTS   │  │ DAEMONSETS    │  │ STATEFULSETS  │       │
│  │               │  │               │  │               │       │
│  │      24       │  │       8       │  │       4       │       │
│  │  22 healthy   │  │   8 healthy   │  │   3 healthy   │       │
│  │   2 degraded  │  │   0 issues    │  │   1 degraded  │       │
│  └───────────────┘  └───────────────┘  └───────────────┘       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Section 5.2: Deployments Table (Enhanced)

```
┌─────────────────────────────────────────────────────────────────┐
│  🚀 DEPLOYMENTS                                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Name                Namespace  Replicas   Health   CPU    Mem  │
│  ──────────────────────────────────────────────────────────────│
│  🟢 nginx-ingress    ingress    3/3 ✓     98/100   240m   512Mi│
│     └─ Ready, No restarts, Updated 2d ago                       │
│                                                                  │
│  🟢 api-server       default    5/5 ✓     95/100   1.2c   2.1Gi│
│     └─ Ready, 2 restarts (last 7d), Updated 1d ago              │
│                                                                  │
│  🟡 worker-queue     jobs       2/3 ⚠     72/100   450m   1.5Gi│
│     └─ 1 pod pending, CrashLoopBackOff, Updated 5h ago          │
│     └─ Action: Check logs for pod worker-queue-7d8b-x9k2m       │
│                                                                  │
│  🔴 batch-processor  jobs       0/2 ✗     35/100   0m     0Mi   │
│     └─ ImagePullBackOff, Failed deployment, Updated 3h ago      │
│     └─ Action: Fix image: docker.io/myapp:v2.3.1                │
│                                                                  │
│  [Show All 24 Deployments] [Export] [Add Deployment]            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Features**:
- Expandable rows showing pod details
- Health score based on replica status, restart count, and age
- Inline action recommendations for degraded workloads
- Color-coded status indicators

### Section 5.3: Pod Details (Drill-Down Modal)

**Triggered by**: Clicking on deployment/pod name

```
┌─────────────────────────────────────────────────────────────────┐
│  📦 POD: worker-queue-7d8b-x9k2m                        [✕ Close]│
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Status: 🟡 Running (CrashLoopBackOff)                          │
│  Namespace: jobs                                                │
│  Node: ip-10-0-1-45 (m5.xlarge)                                 │
│  Age: 5 hours 23 minutes                                        │
│  Restarts: 12 (last restart 8 minutes ago)                      │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ CONTAINERS (2)                                          │   │
│  ├─────────────────────────────────────────────────────────┤   │
│  │                                                          │   │
│  │  🟢 worker-app                                          │   │
│  │     CPU:    245m / 500m (49%)  [▓▓▓▓▓▓▓▓▓▓░░░░░░░░░]   │   │
│  │     Memory: 512Mi / 1Gi (50%)  [▓▓▓▓▓▓▓▓▓▓░░░░░░░░░]   │   │
│  │     FS:     2.3 GB             [▓▓▓▓▓▓░░░░░░░░░░░░░]   │   │
│  │     Image: docker.io/myapp/worker:v1.2.3                │   │
│  │                                                          │   │
│  │  🔴 sidecar-logger                                      │   │
│  │     CPU:    12m / 100m (12%)   [▓▓░░░░░░░░░░░░░░░░░]   │   │
│  │     Memory: 64Mi / 256Mi (25%) [▓▓▓▓▓░░░░░░░░░░░░░░]   │   │
│  │     FS:     0.8 GB             [▓▓░░░░░░░░░░░░░░░░]   │   │
│  │     Image: docker.io/myapp/logger:v0.5.1                │   │
│  │     Status: CrashLoopBackOff                            │   │
│  │     Last Exit Code: 137 (OOMKilled)                     │   │
│  │                                                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
│  🚨 Recommendation:                                             │
│  Sidecar-logger container is being OOMKilled. Increase memory  │
│  limit from 256Mi to 512Mi in deployment spec.                 │
│                                                                  │
│  [View Logs] [View Events] [Edit Deployment] [Delete Pod]      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎨 Visual Design System

### Color Palette (Extended)

```css
/* Status Colors */
--status-critical: #dc2626;      /* Red 600 */
--status-warning: #f59e0b;       /* Amber 500 */
--status-info: #3b82f6;          /* Blue 500 */
--status-success: #16a34a;       /* Green 600 */

/* Background Colors */
--bg-critical-light: #fef2f2;    /* Red 50 */
--bg-warning-light: #fffbeb;     /* Amber 50 */
--bg-info-light: #eff6ff;        /* Blue 50 */
--bg-success-light: #f0fdf4;     /* Green 50 */

/* Border Colors */
--border-critical: #fca5a5;      /* Red 300 */
--border-warning: #fcd34d;       /* Amber 300 */
--border-info: #93c5fd;          /* Blue 300 */
--border-success: #86efac;       /* Green 300 */

/* Premium Gradients */
--gradient-primary: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
--gradient-success: linear-gradient(135deg, #10b981 0%, #059669 100%);
--gradient-warning: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
--gradient-danger: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);

/* Text Colors */
--text-primary: #111827;         /* Gray 900 */
--text-secondary: #6b7280;       /* Gray 500 */
--text-tertiary: #9ca3af;        /* Gray 400 */

/* Neutral Scale */
--neutral-50: #f9fafb;
--neutral-100: #f3f4f6;
--neutral-200: #e5e7eb;
--neutral-300: #d1d5db;
--neutral-500: #6b7280;
--neutral-800: #1f2937;
--neutral-900: #111827;
```

### Typography System

```css
/* Heading Scale */
.text-hero {
  font-size: 3rem;          /* 48px */
  font-weight: 800;
  line-height: 1.1;
  letter-spacing: -0.02em;
}

.text-title {
  font-size: 2rem;          /* 32px */
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.01em;
}

.text-heading {
  font-size: 1.5rem;        /* 24px */
  font-weight: 600;
  line-height: 1.3;
}

.text-subheading {
  font-size: 1.125rem;      /* 18px */
  font-weight: 600;
  line-height: 1.4;
}

/* Body Scale */
.text-body {
  font-size: 0.875rem;      /* 14px */
  font-weight: 400;
  line-height: 1.5;
}

.text-small {
  font-size: 0.75rem;       /* 12px */
  font-weight: 400;
  line-height: 1.5;
}

/* Label Scale */
.text-label {
  font-size: 0.75rem;       /* 12px */
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.text-caption {
  font-size: 0.625rem;      /* 10px */
  font-weight: 500;
  letter-spacing: 0.03em;
}
```

### Spacing System

```css
/* Consistent spacing scale (Tailwind-inspired) */
--space-1: 0.25rem;   /* 4px */
--space-2: 0.5rem;    /* 8px */
--space-3: 0.75rem;   /* 12px */
--space-4: 1rem;      /* 16px */
--space-5: 1.25rem;   /* 20px */
--space-6: 1.5rem;    /* 24px */
--space-8: 2rem;      /* 32px */
--space-10: 2.5rem;   /* 40px */
--space-12: 3rem;     /* 48px */
--space-16: 4rem;     /* 64px */
```

### Shadow System

```css
/* Elevation shadows */
--shadow-sm: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
--shadow-md: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
--shadow-lg: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
--shadow-xl: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
--shadow-2xl: 0 25px 50px -12px rgba(0, 0, 0, 0.25);

/* Colored shadows for status */
--shadow-critical: 0 10px 15px -3px rgba(220, 38, 38, 0.3);
--shadow-warning: 0 10px 15px -3px rgba(245, 158, 11, 0.3);
--shadow-success: 0 10px 15px -3px rgba(22, 163, 74, 0.3);
```

### Border Radius System

```css
--radius-sm: 0.375rem;    /* 6px */
--radius-md: 0.5rem;      /* 8px */
--radius-lg: 0.75rem;     /* 12px */
--radius-xl: 1rem;        /* 16px */
--radius-2xl: 1.5rem;     /* 24px */
--radius-full: 9999px;    /* Fully rounded */
```

---

## 🧩 Reusable Component Library

### 1. Status Badge Component

```html
<template x-if="status === 'healthy'">
  <span class="inline-flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-semibold
               bg-green-100 text-green-800 border border-green-200">
    <span class="w-2 h-2 rounded-full bg-green-500"></span>
    Healthy
  </span>
</template>

<template x-if="status === 'degraded'">
  <span class="inline-flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-semibold
               bg-amber-100 text-amber-800 border border-amber-200">
    <span class="w-2 h-2 rounded-full bg-amber-500"></span>
    Degraded
  </span>
</template>

<template x-if="status === 'critical'">
  <span class="inline-flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-semibold
               bg-red-100 text-red-800 border border-red-200">
    <span class="w-2 h-2 rounded-full bg-red-500 animate-pulse"></span>
    Critical
  </span>
</template>
```

### 2. Metric Card Component

```html
<div class="metric-card group">
  <!-- Top accent border (appears on hover) -->
  <div class="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-blue-500 to-purple-500
              opacity-0 group-hover:opacity-100 transition-opacity rounded-t-lg"></div>

  <!-- Card content -->
  <div class="p-6">
    <!-- Header -->
    <div class="flex items-center justify-between mb-4">
      <div class="flex items-center gap-3">
        <div class="w-12 h-12 rounded-xl bg-blue-50 flex items-center justify-center">
          <i class="fas fa-chart-line text-blue-600 text-xl"></i>
        </div>
        <div>
          <p class="text-xs font-semibold text-gray-500 uppercase tracking-wide">CPU Usage</p>
          <p class="text-2xl font-bold text-gray-900" x-text="`${dashboard.clusterOverview.cpuUsagePercent}%`">
            65%
          </p>
        </div>
      </div>
      <span class="text-xs text-green-600 font-semibold">
        <i class="fas fa-arrow-up"></i> 3%
      </span>
    </div>

    <!-- Visual indicator -->
    <div class="relative">
      <div class="h-2 bg-gray-100 rounded-full overflow-hidden">
        <div class="h-full bg-gradient-to-r from-blue-500 to-purple-500 rounded-full transition-all duration-500"
             :style="`width: ${dashboard.clusterOverview.cpuUsagePercent}%`">
        </div>
      </div>
    </div>

    <!-- Footer -->
    <p class="mt-3 text-xs text-gray-500">
      52 / 80 cores allocated
    </p>
  </div>
</div>
```

### 3. Circular Gauge Component

```html
<div class="gauge-container">
  <!-- SVG Circle -->
  <svg class="gauge-svg" viewBox="0 0 120 120">
    <!-- Background circle -->
    <circle cx="60" cy="60" r="52" class="gauge-bg"></circle>

    <!-- Progress circle -->
    <circle cx="60" cy="60" r="52" class="gauge-progress"
            :stroke="getGaugeColor(dashboard.clusterOverview.cpuUsagePercent)"
            :stroke-dasharray="`${(dashboard.clusterOverview.cpuUsagePercent / 100) * 326.73} 326.73`">
    </circle>
  </svg>

  <!-- Center text -->
  <div class="gauge-text">
    <span class="text-3xl font-bold text-gray-900"
          x-text="`${dashboard.clusterOverview.cpuUsagePercent}%`">
      65%
    </span>
    <span class="text-xs text-gray-500 font-medium">CPU</span>
  </div>
</div>

<script>
function getGaugeColor(percent) {
  if (percent < 70) return '#16a34a'; // Green
  if (percent < 85) return '#f59e0b'; // Amber
  return '#dc2626'; // Red
}
</script>
```

### 4. Progress Bar Component

```html
<div class="space-y-1">
  <div class="flex items-center justify-between text-xs">
    <span class="font-medium text-gray-700">CPU</span>
    <span class="font-semibold" :class="getUsageColorClass(cpuPercent)" x-text="`${cpuPercent}%`">
      82%
    </span>
  </div>
  <div class="h-2 bg-gray-100 rounded-full overflow-hidden">
    <div class="h-full rounded-full transition-all duration-500"
         :class="getUsageColorClass(cpuPercent, true)"
         :style="`width: ${cpuPercent}%`">
    </div>
  </div>
</div>

<script>
function getUsageColorClass(percent, isBg = false) {
  if (percent < 70) return isBg ? 'bg-green-500' : 'text-green-600';
  if (percent < 85) return isBg ? 'bg-amber-500' : 'text-amber-600';
  return isBg ? 'bg-red-500' : 'text-red-600';
}
</script>
```

### 5. Node Card Component

```html
<div class="node-card" :class="getNodeHealthClass(node)">
  <!-- Status indicator -->
  <div class="flex items-center justify-between mb-3">
    <div class="flex items-center gap-2">
      <span class="w-3 h-3 rounded-full" :class="getNodeStatusDotClass(node)"></span>
      <span class="text-sm font-semibold text-gray-900" x-text="node.nodeName">
        Node-01
      </span>
    </div>
    <span class="text-xs px-2 py-1 rounded-md bg-gray-100 text-gray-600 font-medium"
          x-text="node.instanceType">
      m5.xlarge
    </span>
  </div>

  <!-- Resource bars -->
  <div class="space-y-2">
    <div class="space-y-1">
      <div class="flex justify-between text-xs">
        <span class="text-gray-600">CPU</span>
        <span class="font-semibold" x-text="`${node.cpuUsagePercent}%`">82%</span>
      </div>
      <div class="h-1.5 bg-gray-100 rounded-full overflow-hidden">
        <div class="h-full bg-green-500 rounded-full"
             :style="`width: ${node.cpuUsagePercent}%`"></div>
      </div>
    </div>

    <div class="space-y-1">
      <div class="flex justify-between text-xs">
        <span class="text-gray-600">Memory</span>
        <span class="font-semibold" x-text="`${node.memoryUsagePercent}%`">78%</span>
      </div>
      <div class="h-1.5 bg-gray-100 rounded-full overflow-hidden">
        <div class="h-full bg-blue-500 rounded-full"
             :style="`width: ${node.memoryUsagePercent}%`"></div>
      </div>
    </div>
  </div>

  <!-- Pod count -->
  <div class="mt-3 flex items-center justify-between text-xs">
    <span class="text-gray-600">Pods</span>
    <span class="font-semibold text-gray-900" x-text="node.pods">18</span>
  </div>
</div>

<style>
.node-card {
  padding: 1rem;
  background: white;
  border: 2px solid #e5e7eb;
  border-radius: 0.75rem;
  transition: all 0.2s;
}

.node-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);
}

.node-card.optimal {
  border-color: #86efac;
  background: linear-gradient(to bottom right, white, #f0fdf4);
}

.node-card.warning {
  border-color: #fcd34d;
  background: linear-gradient(to bottom right, white, #fffbeb);
}

.node-card.critical {
  border-color: #fca5a5;
  background: linear-gradient(to bottom right, white, #fef2f2);
}
</style>
```

### 6. Alert Card Component

```html
<div class="alert-card" :class="getAlertTypeClass(alert.priority)">
  <div class="flex items-start gap-3">
    <!-- Icon -->
    <div class="flex-shrink-0 w-10 h-10 rounded-full flex items-center justify-center"
         :class="getAlertIconBgClass(alert.priority)">
      <i :class="getAlertIconClass(alert.priority)" class="text-white"></i>
    </div>

    <!-- Content -->
    <div class="flex-1 min-w-0">
      <div class="flex items-center justify-between mb-1">
        <h4 class="text-sm font-semibold text-gray-900" x-text="alert.rule">
          Container drift detected
        </h4>
        <span class="text-xs text-gray-500" x-text="getTimeAgo(alert.timestamp)">
          2 minutes ago
        </span>
      </div>

      <p class="text-xs text-gray-600 mb-2">
        Pod: <span class="font-medium" x-text="alert.pod">nginx-7d8b-x9k2m</span> |
        Namespace: <span class="font-medium" x-text="alert.namespace">production</span>
      </p>

      <div class="flex items-center gap-2">
        <button class="text-xs font-medium text-blue-600 hover:text-blue-700">
          View Details
        </button>
        <button class="text-xs font-medium text-gray-600 hover:text-gray-700">
          Silence
        </button>
      </div>
    </div>
  </div>
</div>

<style>
.alert-card {
  padding: 1rem;
  border-radius: 0.75rem;
  border-left-width: 4px;
  transition: all 0.2s;
}

.alert-card:hover {
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
}

.alert-card.critical {
  background: linear-gradient(to right, #fef2f2, white);
  border-left-color: #dc2626;
}

.alert-card.warning {
  background: linear-gradient(to right, #fffbeb, white);
  border-left-color: #f59e0b;
}

.alert-card.info {
  background: linear-gradient(to right, #eff6ff, white);
  border-left-color: #3b82f6;
}
</style>
```

---

## 📱 Responsive Design Strategy

### Breakpoint System

```css
/* Mobile-first approach */
/* xs: 0-639px (default) */
/* sm: 640px+ */
@media (min-width: 640px) { ... }

/* md: 768px+ */
@media (min-width: 768px) { ... }

/* lg: 1024px+ */
@media (min-width: 1024px) { ... }

/* xl: 1280px+ */
@media (min-width: 1280px) { ... }

/* 2xl: 1536px+ */
@media (min-width: 1536px) { ... }
```

### Layout Adjustments by Breakpoint

#### Mobile (< 768px)
- Single column layout
- Stack metric cards vertically
- Hide secondary information
- Simplified tables (show only critical columns)
- Bottom navigation for tabs
- Collapsible sections by default

#### Tablet (768px - 1024px)
- 2-column grid for metric cards
- Show abbreviated tables
- Side navigation with collapsed state
- Horizontal scrolling for wide content

#### Desktop (> 1024px)
- Full multi-column layouts (3-4 columns)
- Expanded tables with all columns
- Fixed sidebar navigation
- Hover states and tooltips
- Multi-panel views

---

## 🎯 Implementation Priority

### Phase 1: Core Redesign (Week 1)
1. ✅ Hero Section with Health Score
2. ✅ Quick Wins / Action Panel
3. ✅ New Tab Navigation
4. ✅ Overview Tab (24h trends)
5. ✅ Cost Tab redesign

### Phase 2: Performance & Security (Week 2)
1. ✅ Performance Tab with node grid
2. ✅ Security Tab with Cast.ai-style cards
3. ✅ Vulnerability management section
4. ✅ Falco alerts redesign

### Phase 3: Workloads & Polish (Week 3)
1. ✅ Workloads Tab
2. ✅ Pod details modal
3. ✅ Reusable component library
4. ✅ Responsive design implementation
5. ✅ Animation and transition polish

---

## 🔧 Technical Implementation Notes

### 1. Data Calculations for New Metrics

#### Health Score Calculation
```javascript
getClusterHealthScore() {
  const weights = {
    resource: 0.4,  // 40% weight
    cost: 0.3,      // 30% weight
    security: 0.3   // 30% weight
  };

  const resourceScore = this.getResourceScore();
  const costScore = this.getCostScore();
  const securityScore = this.getSecurityScore();

  return Math.round(
    resourceScore * weights.resource +
    costScore * weights.cost +
    securityScore * weights.security
  );
}

getResourceScore() {
  const cpu = this.dashboard.clusterOverview.cpuUsagePercent;
  const mem = this.dashboard.clusterOverview.memoryUsagePercent;
  const avgUtil = (cpu + mem) / 2;

  // Optimal range: 40-75%
  if (avgUtil >= 40 && avgUtil <= 75) return 100;
  if (avgUtil < 40) return Math.max(0, 100 - (40 - avgUtil) * 2);
  return Math.max(0, 100 - (avgUtil - 75) * 3);
}

getCostScore() {
  const efficiency = this.getCostEfficiency();
  return efficiency; // Already 0-100
}

getSecurityScore() {
  const critical = this.dashboard.securityMetrics?.eventsByPriority?.['4'] || 0;
  const high = this.dashboard.securityMetrics?.eventsByPriority?.['3'] || 0;

  let score = 100;
  score -= critical * 10; // -10 per critical
  score -= high * 5;      // -5 per high

  return Math.max(0, score);
}
```

#### Potential Savings Calculation
```javascript
getPotentialSavings() {
  const nodeCosts = this.dashboard.costMetrics.nodeCosts;

  const underutilizedNodes = nodeCosts.filter(node =>
    node.cpuUsagePercent < 40 || node.memoryUsagePercent < 40
  );

  // Calculate waste: nodes that could be consolidated
  const wastedCost = underutilizedNodes.reduce((sum, node) => {
    const wastePercent = 100 - Math.max(node.cpuUsagePercent, node.memoryUsagePercent);
    return sum + (node.dailyCost * (wastePercent / 100));
  }, 0);

  return {
    daily: wastedCost,
    monthly: wastedCost * 30,
    affectedNodes: underutilizedNodes.length
  };
}
```

#### Quick Wins Generation
```javascript
getQuickWins() {
  const wins = [];

  // Security wins
  const criticalVulns = this.dashboard.kubescape?.totalCritical || 0;
  if (criticalVulns > 0) {
    wins.push({
      priority: 1,
      type: 'security',
      icon: '🔴',
      title: `Fix ${criticalVulns} Critical Security Vulnerabilities`,
      impact: 'Prevent potential exploits',
      actions: ['View Details', 'Auto-Fix Available']
    });
  }

  // Cost wins
  const savings = this.getPotentialSavings();
  if (savings.affectedNodes > 0) {
    wins.push({
      priority: 2,
      type: 'cost',
      icon: '⚡',
      title: `Optimize ${savings.affectedNodes} Underutilized Nodes`,
      impact: `Save $${savings.daily.toFixed(2)}/day`,
      actions: ['View Nodes', 'Apply Recommendations']
    });
  }

  // Karpenter win
  if (!this.karpenterEnabled) {
    wins.push({
      priority: 3,
      type: 'optimization',
      icon: '💰',
      title: 'Enable Karpenter Autoscaling',
      impact: `Save ~$${(savings.daily * 0.6).toFixed(2)}/day with intelligent scaling`,
      actions: ['Configure Now', 'Learn More']
    });
  }

  return wins.sort((a, b) => a.priority - b.priority);
}
```

### 2. Chart Integration

**Recommended Library**: ApexCharts (lightweight, responsive)

```html
<!-- Add to head -->
<script src="https://cdn.jsdelivr.net/npm/apexcharts"></script>

<!-- Sparkline implementation -->
<script>
function renderTrendChart(elementId, data, label) {
  const options = {
    series: [{
      name: label,
      data: data
    }],
    chart: {
      type: 'area',
      height: 100,
      sparkline: {
        enabled: true
      }
    },
    stroke: {
      curve: 'smooth',
      width: 2
    },
    fill: {
      type: 'gradient',
      gradient: {
        shadeIntensity: 1,
        opacityFrom: 0.4,
        opacityTo: 0.1
      }
    },
    colors: ['#3b82f6'],
    tooltip: {
      enabled: true,
      y: {
        formatter: (val) => `${val}${label.includes('%') ? '%' : ''}`
      }
    }
  };

  const chart = new ApexCharts(document.querySelector(`#${elementId}`), options);
  chart.render();
}

// Usage
renderTrendChart('cpu-trend', [58, 62, 65, 68, 65, 63, 65], 'CPU %');
</script>
```

### 3. Local Storage for Historical Data

```javascript
// Store metrics every 5 minutes
function storeMetricSnapshot() {
  const snapshot = {
    timestamp: Date.now(),
    cpu: this.dashboard.clusterOverview.cpuUsagePercent,
    memory: this.dashboard.clusterOverview.memoryUsagePercent,
    cost: this.dashboard.costMetrics.dailyCost,
    securityEvents: this.dashboard.securityMetrics.totalEvents
  };

  let history = JSON.parse(localStorage.getItem('clusterHistory') || '[]');
  history.push(snapshot);

  // Keep only last 24 hours (288 snapshots at 5min intervals)
  if (history.length > 288) {
    history = history.slice(-288);
  }

  localStorage.setItem('clusterHistory', JSON.stringify(history));
}

// Retrieve trend data
function getTrendData(metric, hours = 24) {
  const history = JSON.parse(localStorage.getItem('clusterHistory') || '[]');
  const cutoff = Date.now() - (hours * 60 * 60 * 1000);

  return history
    .filter(snap => snap.timestamp > cutoff)
    .map(snap => snap[metric]);
}
```

### 4. Animation Timing

```css
/* Consistent animation durations */
--duration-fast: 150ms;      /* Button hover, small transitions */
--duration-normal: 300ms;    /* Card hover, modal open */
--duration-slow: 500ms;      /* Progress bars, gauge animations */
--duration-slower: 1000ms;   /* Page transitions, large animations */

/* Easing functions */
--ease-in: cubic-bezier(0.4, 0, 1, 1);
--ease-out: cubic-bezier(0, 0, 0.2, 1);
--ease-in-out: cubic-bezier(0.4, 0, 0.2, 1);
--ease-bounce: cubic-bezier(0.68, -0.55, 0.265, 1.55);
```

---

## 📚 Component Usage Examples

### Example 1: Building the Hero Section

```html
<div class="bg-white rounded-2xl shadow-lg p-8 mb-8">
  <!-- Cluster Identity -->
  <div class="flex items-center justify-between mb-6">
    <div class="flex items-center gap-4">
      <span class="w-4 h-4 rounded-full bg-green-500 animate-pulse"></span>
      <h1 class="text-3xl font-extrabold text-gray-900" x-text="clusterName">
        eks-production-us-east-1
      </h1>
    </div>
    <p class="text-sm text-gray-500">
      Last sync: <span x-text="getTimeSince(lastUpdateTimestamp)">2m ago</span>
    </p>
  </div>

  <!-- Cluster Badges -->
  <div class="flex items-center gap-3 mb-8">
    <span class="px-3 py-1 bg-blue-50 text-blue-700 rounded-lg text-sm font-medium"
          x-text="`v${clusterVersion}`">
      v1.28
    </span>
    <span class="px-3 py-1 bg-gray-50 text-gray-700 rounded-lg text-sm font-medium"
          x-text="region">
      us-east-1
    </span>
    <span class="px-3 py-1 bg-gray-50 text-gray-700 rounded-lg text-sm font-medium"
          x-text="`${dashboard?.clusterOverview?.totalNodes || 0} nodes`">
      12 nodes
    </span>
    <span class="px-3 py-1 bg-gray-50 text-gray-700 rounded-lg text-sm font-medium"
          x-text="`${dashboard?.clusterOverview?.totalPods || 0} pods`">
      156 pods
    </span>
  </div>

  <!-- Quick Metrics Row -->
  <div class="grid grid-cols-3 gap-6">
    <!-- Cost Today -->
    <div class="p-4 bg-gradient-to-br from-blue-50 to-white rounded-xl border border-blue-100">
      <p class="text-xs font-semibold text-blue-600 uppercase tracking-wide mb-2">Cost Today</p>
      <p class="text-3xl font-bold text-gray-900"
         x-text="`$${dashboard?.costMetrics?.dailyCost?.toFixed(2) || '0.00'}`">
        $127.45
      </p>
      <p class="text-xs text-green-600 font-medium mt-1">
        <i class="fas fa-arrow-up"></i> 5% vs yesterday
      </p>
    </div>

    <!-- Efficiency -->
    <div class="p-4 bg-gradient-to-br from-green-50 to-white rounded-xl border border-green-100">
      <p class="text-xs font-semibold text-green-600 uppercase tracking-wide mb-2">Efficiency</p>
      <p class="text-3xl font-bold text-gray-900" x-text="`${getCostEfficiency()}%`">
        68%
      </p>
      <p class="text-xs text-amber-600 font-medium mt-1">
        Medium - Save $41/day
      </p>
    </div>

    <!-- Security -->
    <div class="p-4 bg-gradient-to-br from-red-50 to-white rounded-xl border border-red-100">
      <p class="text-xs font-semibold text-red-600 uppercase tracking-wide mb-2">Security</p>
      <p class="text-3xl font-bold text-gray-900"
         x-text="dashboard?.securityMetrics?.totalEvents || 0">
        12
      </p>
      <p class="text-xs text-red-600 font-medium mt-1">
        <span x-text="dashboard?.securityMetrics?.eventsByPriority?.['4'] || 0">3</span> Critical
      </p>
    </div>
  </div>

  <!-- Optimization Tip -->
  <div class="mt-6 p-4 bg-purple-50 border border-purple-200 rounded-xl flex items-start gap-3"
       x-show="getQuickWins().length > 0">
    <div class="w-10 h-10 bg-purple-500 rounded-lg flex items-center justify-center flex-shrink-0">
      <i class="fas fa-lightbulb text-white"></i>
    </div>
    <div class="flex-1">
      <p class="text-sm font-semibold text-purple-900 mb-1">Optimization Tip</p>
      <p class="text-sm text-purple-700" x-text="getQuickWins()[0]?.title">
        8 nodes are underutilized. Consider enabling Karpenter autoscaling to save ~$35/day.
      </p>
    </div>
  </div>
</div>
```

### Example 2: Node Health Grid

```html
<div class="bg-white rounded-2xl shadow-lg p-8">
  <h3 class="text-xl font-bold text-gray-900 mb-6">
    🖥️ Node Health Matrix
  </h3>

  <!-- Node Grid -->
  <div class="grid grid-cols-4 gap-4">
    <template x-for="node in dashboard.resourceUtilization.nodes" :key="node.nodeName">
      <div class="node-card" :class="getNodeHealthClass(node)">
        <!-- Status Indicator -->
        <div class="flex items-center justify-between mb-3">
          <div class="flex items-center gap-2">
            <span class="w-3 h-3 rounded-full" :class="getNodeStatusDotClass(node)"></span>
            <span class="text-sm font-semibold text-gray-900" x-text="node.nodeName.substring(0, 10)">
              Node-01
            </span>
          </div>
          <span class="text-xs px-2 py-1 rounded-md bg-gray-100 text-gray-600 font-medium"
                x-text="node.instanceType">
            m5.xlarge
          </span>
        </div>

        <!-- Resource Bars -->
        <div class="space-y-2">
          <!-- CPU -->
          <div class="space-y-1">
            <div class="flex justify-between text-xs">
              <span class="text-gray-600">CPU</span>
              <span class="font-semibold"
                    :class="getUsageColorClass(node.cpuUsagePercent)"
                    x-text="`${node.cpuUsagePercent?.toFixed(0)}%`">
                82%
              </span>
            </div>
            <div class="h-1.5 bg-gray-100 rounded-full overflow-hidden">
              <div class="h-full rounded-full transition-all duration-500"
                   :class="getUsageColorClass(node.cpuUsagePercent, true)"
                   :style="`width: ${node.cpuUsagePercent}%`">
              </div>
            </div>
          </div>

          <!-- Memory -->
          <div class="space-y-1">
            <div class="flex justify-between text-xs">
              <span class="text-gray-600">Memory</span>
              <span class="font-semibold"
                    :class="getUsageColorClass(node.memoryUsagePercent)"
                    x-text="`${node.memoryUsagePercent?.toFixed(0)}%`">
                78%
              </span>
            </div>
            <div class="h-1.5 bg-gray-100 rounded-full overflow-hidden">
              <div class="h-full rounded-full transition-all duration-500"
                   :class="getUsageColorClass(node.memoryUsagePercent, true)"
                   :style="`width: ${node.memoryUsagePercent}%`">
              </div>
            </div>
          </div>
        </div>

        <!-- Pod Count -->
        <div class="mt-3 flex items-center justify-between text-xs">
          <span class="text-gray-600">Pods</span>
          <span class="font-semibold text-gray-900" x-text="node.pods">18</span>
        </div>
      </div>
    </template>
  </div>

  <!-- Legend -->
  <div class="mt-6 flex items-center gap-4 text-xs">
    <span class="flex items-center gap-2">
      <span class="w-3 h-3 rounded-full bg-green-500"></span>
      Optimal (4)
    </span>
    <span class="flex items-center gap-2">
      <span class="w-3 h-3 rounded-full bg-amber-500"></span>
      Moderate (2)
    </span>
    <span class="flex items-center gap-2">
      <span class="w-3 h-3 rounded-full bg-red-500"></span>
      Underutilized (2)
    </span>
  </div>
</div>
```

---

## 🚀 Getting Started

### Step 1: Backup Current File
```bash
cp frontend-app/eks-details.html frontend-app/eks-details.backup.html
```

### Step 2: Add New CSS Variables
Add the extended color palette and design system variables to the `<style>` section.

### Step 3: Implement Overview Tab First
Start with the new "Overview" tab as it's the most impactful change.

### Step 4: Refactor Existing Tabs
Gradually update Cost, Performance, Security, and Workloads tabs.

### Step 5: Add Chart Library
Integrate ApexCharts for trend visualizations.

### Step 6: Test Responsiveness
Verify all breakpoints work correctly.

---

## 🎉 Expected Impact

### User Experience Improvements
- **50% faster insight discovery** - Critical info front and center
- **Reduced cognitive load** - Clear visual hierarchy
- **Actionable intelligence** - Specific recommendations, not just data
- **Mobile-friendly** - Fully responsive design

### Business Value
- **Faster incident response** - Health score alerts immediately
- **Cost optimization** - Clear waste identification
- **Security compliance** - Vulnerability tracking at a glance
- **Customer satisfaction** - Professional, modern interface

---

## 📞 Support & Questions

For implementation assistance, refer to:
- Cast.ai dashboard screenshots (provided)
- PrometheusService.java for data mappings
- This comprehensive design document

---

**Document Version**: 1.0
**Last Updated**: 2026-01-08
**Author**: XamOps UI/UX Team
**Status**: Ready for Implementation
