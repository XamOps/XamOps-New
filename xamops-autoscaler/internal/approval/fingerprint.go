package approval

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"strings"
)

// DedupeKey is the one function that builds a proposal's dedupe key:
//
//	<actionType>:<operation>:<originController>:<target.name>
//
// Node names are DNS-1123 subdomains and never contain ':', so the key is
// unambiguous. Each origin owns its own row, so one node can have two rows.
func DedupeKey(actionType, operation, originController, targetName string) string {
	return actionType + ":" + operation + ":" + originController + ":" + targetName
}

// NodeFingerprintInput is everything an approval is bound to for a node action:
// which node (uid), which operation and origin, whether it was allowed, and the
// identical set of refusal and blocking reasons. Magnitudes (utilisation, pod counts,
// moves, PDB counters, timestamps) are deliberately not part of it.
type NodeFingerprintInput struct {
	ActionType   string
	Operation    string
	Origin       string
	Node         string
	UID          string
	Verdict      string
	RefusalCodes []string
	Drainable    bool
	RefusedCodes []string
	BlockedCodes []string
	Strands      bool

	// NODE_CONSOLIDATE only.
	MoveType   string
	NodeGroup  string
	ProviderID string
}

type fpDrain struct {
	V            int      `json:"v"`
	ActionType   string   `json:"actionType"`
	Operation    string   `json:"operation"`
	Origin       string   `json:"origin"`
	Node         string   `json:"node"`
	UID          string   `json:"uid"`
	Verdict      string   `json:"verdict"`
	RefusalCodes []string `json:"refusalCodes"`
	Drainable    bool     `json:"drainable"`
	RefusedCodes []string `json:"refusedCodes"`
	BlockedCodes []string `json:"blockedCodes"`
	Strands      bool     `json:"strands"`
}

type fpConsolidate struct {
	fpDrain
	MoveType   string `json:"moveType"`
	NodeGroup  string `json:"nodeGroup"`
	ProviderID string `json:"providerId"`
}

// NodeFingerprint returns "sha256:" + 64 lowercase hex of the canonical JSON (struct
// field order, no whitespace, every list sorted and de-duplicated).
func NodeFingerprint(in NodeFingerprintInput) string {
	d := fpDrain{
		V: 1, ActionType: in.ActionType, Operation: in.Operation, Origin: in.Origin,
		Node: in.Node, UID: in.UID, Verdict: in.Verdict,
		RefusalCodes: SortedUnique(in.RefusalCodes), Drainable: in.Drainable,
		RefusedCodes: SortedUnique(in.RefusedCodes), BlockedCodes: SortedUnique(in.BlockedCodes),
		Strands: in.Strands,
	}
	var v interface{} = d
	if in.ActionType == TypeNodeConsolidate {
		v = fpConsolidate{fpDrain: d, MoveType: in.MoveType, NodeGroup: in.NodeGroup, ProviderID: in.ProviderID}
	}
	b, err := json.Marshal(v)
	if err != nil { // plain structs of strings and bools cannot fail to marshal
		panic("approval: fingerprint marshal: " + err.Error())
	}
	sum := sha256.Sum256(b)
	return "sha256:" + hex.EncodeToString(sum[:])
}

// SortedUnique returns a sorted, de-duplicated copy (never nil, so it marshals as []).
func SortedUnique(in []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, s := range in {
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	sort.Strings(out)
	return out
}

// ReasonCodes splits one finding's Reason (applicable codes joined with "; ") into
// its codes. A code keeps any ":" suffix that is part of it (pdb-no-disruptions:ns/name).
func ReasonCodes(reason string) []string {
	var out []string
	for _, c := range strings.Split(reason, "; ") {
		if c = strings.TrimSpace(c); c != "" {
			out = append(out, c)
		}
	}
	return out
}
