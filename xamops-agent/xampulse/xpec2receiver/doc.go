// Package xpec2receiver is the XamPulse EC2 receiver ("xampulse_ec2"): the signals that explain why an EC2 instance
// is slow while CPU looks fine, read on the instance itself at no AWS cost.
//
// Metrics (all names under xp.* so they never collide with upstream semantic conventions):
//
//   - xp.pressure.stall_time (Sum, cumulative, monotonic, seconds; attributes resource=cpu|memory|io,
//     level=some|full). From /proc/pressure/*: the total time tasks were stalled waiting for that resource. Its rate
//     is the fraction of time stalled (0..1), the best single "is this box struggling" signal. Absent when the kernel
//     has PSI disabled.
//   - xp.ena.allowance_exceeded (Sum, cumulative, monotonic, {packet}; attributes device, allowance=bw_in|bw_out|
//     pps|conntrack|linklocal). From the ENA driver's ethtool statistics: packets queued or dropped because the
//     instance went over an AWS network allowance. Any increase means AWS, not the OS, limited the traffic.
//   - xp.ena.conntrack_allowance_available (Gauge, {connection}; attribute device). Tracked connections the
//     instance can still open before hitting its conntrack allowance (Nitro, ENA driver 2.8.1 or later).
//   - xp.ec2.spot.interruption (Gauge 0/1; attributes action, time when 1). The two-minute Spot notice from IMDS.
//   - xp.ec2.rebalance_recommendation (Gauge 0/1; attribute notice_time when 1). Elevated Spot interruption risk.
//   - xp.ec2.maintenance.scheduled (Gauge, {event}). Active scheduled maintenance events from IMDS.
//   - xp.net.listening (Gauge 1, {socket}; attributes protocol=tcp|udp, port, bind=all|loopback|address, address).
//     Listening TCP sockets and bound UDP sockets below 32768 from /proc/net, for the exposure check (what a
//     security group rule could expose). No process names: mapping sockets to processes needs root.
//
// Every source is optional: a host without PSI, without an ENA interface, or not on EC2 simply produces no points
// for that source (logged once), never an error every interval. ethtool statistics and /proc/pressure are readable
// without privileges; IMDS is read with an IMDSv2 session token.
package xpec2receiver
