package controller

import (
	"testing"

	"k8s.io/client-go/kubernetes/fake"
)

// writeActions lists every recorded action that is not a read.
func writeActions(c *fake.Clientset) []string {
	var out []string
	for _, a := range c.Actions() {
		switch a.GetVerb() {
		case "get", "list", "watch":
			continue
		}
		s := a.GetVerb() + " " + a.GetResource().Resource
		if a.GetSubresource() != "" {
			s += "/" + a.GetSubresource()
		}
		out = append(out, s)
	}
	return out
}

func assertOnlyReads(t *testing.T, c *fake.Clientset) {
	t.Helper()
	if w := writeActions(c); len(w) != 0 {
		t.Errorf("expected only get/list/watch, got writes: %v", w)
	}
}

func assertNoPodDelete(t *testing.T, c *fake.Clientset) {
	t.Helper()
	for _, a := range c.Actions() {
		if (a.GetVerb() == "delete" || a.GetVerb() == "deletecollection") && a.GetResource().Resource == "pods" {
			t.Errorf("pod %s issued", a.GetVerb())
		}
	}
}
