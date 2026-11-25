// Package xampulse carries the static assets of the XamPulse agent (collector config templates) so they are
// compiled into the binary. Behaviour lives in internal/xampulse.
package xampulse

import "embed"

// Templates holds the OpenTelemetry Collector configuration templates, one per platform.
//
//go:embed templates/*.tmpl
var Templates embed.FS
