# Bolt's Journal - Critical Learnings

## 2025-05-15 - Parallelizing Network Loops
**Learning:** Sequential HTTP calls in dashboard aggregation methods (like SonarQube metrics) created a linear latency bottleneck ($O(N)$) that significantly delayed UI rendering.
**Action:** Use `parallelStream()` combined with thread-safe aggregators like `LongAdder` and `DoubleAdder` to reduce latency to approximately $O(1)$ relative to the number of projects/accounts, provided the thread pool is sufficient.
