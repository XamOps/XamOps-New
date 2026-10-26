// Package obslog is the agent's structured (JSON-field) logging helper for the
// drain/gate/cycle lines (hardening-design.md 'observability baseline'). It wraps the
// standard library log/slog (Go 1.21+) configured as a JSON handler writing to stderr,
// so each line carries typed fields (operationId, node, controller, mode, result)
// alongside a message.
//
// IMPORTANT: this ADDS structured lines; the existing human-readable log.Printf lines
// the node-regression relies on ('Gates —', 'policy-fetched', 'Automation mode=') are
// kept intact. The structured emitters below also embed those exact substrings in
// their messages so neither form drops the information.
package obslog

import (
	"log/slog"
	"os"
)

// logger is the shared JSON handler. Package-level so call sites stay terse; it writes
// to stderr like the standard log package the agent already uses.
var logger = slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))

// Drain emits a structured drain line with the operationId/node/controller/mode/result
// fields. msg is the human message (the executor also keeps its free-text Drain[...]
// log line).
func Drain(msg, operationID, node, controller, mode, result string) {
	logger.Info(msg,
		slog.String("event", "drain"),
		slog.String("operationId", operationID),
		slog.String("node", node),
		slog.String("controller", controller),
		slog.String("mode", mode),
		slog.String("result", result),
	)
}

// Cycle emits a structured cycle-level line (e.g. gate decisions, policy mode).
func Cycle(msg string, attrs ...slog.Attr) {
	logger.LogAttrs(nil, slog.LevelInfo, msg, append([]slog.Attr{slog.String("event", "cycle")}, attrs...)...)
}

// Gate emits a structured gate-decision line for one controller.
func Gate(msg, controller, mode string) {
	logger.Info(msg,
		slog.String("event", "gate"),
		slog.String("controller", controller),
		slog.String("mode", mode),
	)
}
