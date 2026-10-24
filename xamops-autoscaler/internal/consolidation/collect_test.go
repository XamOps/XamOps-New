package consolidation

import (
	"bytes"
	"context"
	"testing"

	"k8s.io/client-go/kubernetes/fake"
)

// ANALYSIS mode takes no actions (testing spec §4): Run against a fake clientset
// issues list calls only (no create/update/patch/delete, no eviction subresource),
// and its plan equals Analyze over the same objects.
func TestRunListsOnlyAndMatchesAnalyze(t *testing.T) {
	for name, build := range map[string]func() *objects{
		"blocked-reasons": blockedReasonsFixture,
		"empty-and-drain": emptyAndDrainFixture,
	} {
		t.Run(name, func(t *testing.T) {
			o := build()
			c := fake.NewSimpleClientset(o.runtimeObjects()...)
			c.ClearActions()

			p, err := Run(context.Background(), c, testOptions(), goldenNow)
			if err != nil {
				t.Fatal(err)
			}
			acts := c.Actions()
			if len(acts) != 6 {
				t.Errorf("%d API call(s), want 6 lists", len(acts))
			}
			for _, a := range acts {
				if a.GetVerb() != "list" || a.GetSubresource() != "" {
					t.Errorf("non-list call: %s %s/%s", a.GetVerb(), a.GetResource().Resource, a.GetSubresource())
				}
			}
			got, want := planJSON(t, p), planJSON(t, Analyze(o.input(), testOptions(), goldenNow))
			if !bytes.Equal(got, want) {
				t.Errorf("Run plan differs from Analyze:\n run:\n%s\n analyze:\n%s", got, want)
			}
		})
	}
}
