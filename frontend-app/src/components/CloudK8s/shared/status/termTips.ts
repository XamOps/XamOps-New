/**
 * The "What's this?" term table (glossary-additions §1). Each term's text is the exact glossary
 * wording, with the small-grey source line naming the page where the rule lives. The same text is
 * used on every page. Tokens like {headroom} / {pct} / {cooldown} are left in the text; a caller may
 * substitute them before passing the entry to TermTip.
 */
export interface TermTipEntry {
  /** The term as shown. */
  term: string;
  /** The exact tooltip text. */
  text: string;
  /** The small-grey source line. */
  source: string;
}

export const TERM_TIPS: Record<string, TermTipEntry> = {
  '95th percentile': {
    term: '95th percentile',
    text: 'Of all the 5-minute CPU readings in the look-back window, 95% are at or below this value. XamOps sizes CPU for busy times without letting the busiest 5% of moments set the size.',
    source: 'Policies: CPU target',
  },
  headroom: {
    term: 'headroom',
    text: 'Extra room XamOps adds on top of what it measured, so a workload can grow a little before it reaches its request. Today it is {headroom}%.',
    source: 'Policies: Headroom',
  },
  'wait after a change': {
    term: 'wait after a change',
    text: 'After XamOps changes a workload, it waits {cooldown} minutes before suggesting another change, so it measures the new size first. An out-of-memory kill can still raise memory at once.',
    source: 'Policies: Wait after a change',
  },
  'in place': {
    term: 'in place',
    text: 'Kubernetes changes the CPU and memory of a running pod without restarting it. The pod keeps its name, its containers and its restart count.',
    source: 'Policies: How changes are applied',
  },
  'QoS class': {
    term: 'QoS class',
    text: 'How Kubernetes ranks a pod when its node runs short: Guaranteed (requests equal limits), Burstable (requests below limits) or BestEffort (none set). A running pod can\'t change class, so XamOps keeps it.',
    source: 'Kubernetes QoS classes',
  },
  replicas: {
    term: 'replicas',
    text: 'How many copies of the pod the workload runs. XamOps sizes each copy; an HPA, if there is one, changes how many copies there are.',
    source: 'HPA',
  },
  request: {
    term: 'request',
    text: 'The CPU and memory Kubernetes sets aside for a container on its node, whether the container uses it or not. Requests decide where pods fit and how many nodes you pay for.',
    source: 'Capacity terms',
  },
  limit: {
    term: 'limit',
    text: 'The most CPU or memory a container may use. Above its CPU limit it is slowed down (throttled); above its memory limit it is stopped and restarted.',
    source: 'Capacity terms',
  },
  throttled: {
    term: 'throttled',
    text: 'Held back by its CPU limit: the container wanted more CPU than its limit allows, so Kubernetes paused it for part of each 100 ms period.',
    source: 'Workload autoscaler: Why this suggestion',
  },
  deferred: {
    term: 'deferred',
    text: "Kubernetes accepted the new size but couldn't give it yet, because the pod's node has no free room. XamOps doesn't leave a change waiting: it puts the pod back.",
    source: 'Activity: results',
  },
  'test pod': {
    term: 'test pod',
    text: "XamOps resizes one pod first and waits for Kubernetes to confirm it. Only then does it resize the rest, two at a time, and it stops at the first pod that doesn't take the change.",
    source: 'Policies: How changes are applied',
  },
  allocatable: {
    term: 'allocatable',
    text: 'What Kubernetes can give to pods on a node: its CPU and memory minus what the node keeps for the system.',
    source: 'Capacity terms',
  },
  'reserved, unused': {
    term: 'reserved, unused',
    text: 'Requested minus used: CPU or memory pods have set aside and aren\'t using. The Workload autoscaler gives this back.',
    source: 'Capacity terms',
  },
  'not requested': {
    term: 'not requested',
    text: 'Allocatable minus requested: free room on the nodes that no pod has set aside. Bin-packing and node autoscaling remove this.',
    source: 'Capacity terms',
  },
  'saving now': {
    term: 'saving now',
    text: 'What the changes in place save each month, against what the same pods requested before XamOps changed them. Price: <price basis>.',
    source: 'Savings: method',
  },
  'ready to apply': {
    term: 'ready to apply',
    text: 'What the suggestions waiting for you would save each month if you applied them now. Nothing is saved until Kubernetes confirms a change.',
    source: 'Savings: method',
  },
  'room on its node': {
    term: 'room on its node',
    text: "The node's allocatable CPU minus the requests of every pod on it. A resize that needs more than this is deferred by Kubernetes.",
    source: 'Workload detail: Make room',
  },
  'time at its maximum': {
    term: 'time at its maximum',
    text: 'The share of minutes in the window when the HPA ran its maximum number of replicas. Above about 80%, it would usually add pods if it could.',
    source: 'HPA',
  },
  'in effect': {
    term: 'in effect',
    text: "The size Kubernetes is actually giving the container, read from the pod's status, not the size written in its spec. They differ while a resize waits or fails.",
    source: 'Workload detail',
  },
};
