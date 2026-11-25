package xpec2receiver

import (
	"context"
	"errors"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/consumer"
	"go.opentelemetry.io/collector/receiver"
	"go.opentelemetry.io/collector/scraper"
	"go.opentelemetry.io/collector/scraper/scraperhelper"
)

// TypeStr is the receiver's name in collector configuration.
const TypeStr = "xampulse_ec2"

var componentType = component.MustNewType(TypeStr)

// NewFactory creates the factory for the xampulse_ec2 receiver.
func NewFactory() receiver.Factory {
	return receiver.NewFactory(componentType, func() component.Config { return defaultConfig() },
		receiver.WithMetrics(createMetrics, component.StabilityLevelAlpha))
}

func createMetrics(_ context.Context, set receiver.Settings, cfg component.Config, next consumer.Metrics) (receiver.Metrics, error) {
	c, ok := cfg.(*Config)
	if !ok {
		return nil, errors.New("unexpected config type")
	}
	s := newEC2Scraper(c, set.Logger, defaultSources(c))
	sc, err := scraper.NewMetrics(s.scrape, scraper.WithStart(s.begin))
	if err != nil {
		return nil, err
	}
	return scraperhelper.NewMetricsController(&c.ControllerConfig, set, next, scraperhelper.AddMetricsScraper(componentType, sc))
}
