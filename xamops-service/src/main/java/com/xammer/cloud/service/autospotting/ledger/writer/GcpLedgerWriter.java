package com.xammer.cloud.service.autospotting.ledger.writer;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * {@code ledger-writer:gcp} (plan-phase-30.md 30.4): the Phase 17 writer with the GCP provider registered. It has its
 * own job lease, the GCP calc version {@code gcp-1}, and is gated by {@code autospotting.ledger.gcp.writer-enabled}
 * and the {@code LEDGER:gcp} switch. With no managed GCP group it finds no candidate account and writes no row. Shadow
 * only: it measures and never invoices.
 */
@Service
public class GcpLedgerWriter extends LedgerWriter {
    public static final String GCP_JOB = "ledger-writer:gcp";
    public static final String GCP_PROVIDER = "GCP";

    @Autowired
    public GcpLedgerWriter(@Qualifier("gcpLedgerInputs") LedgerInputsSource source, LedgerRunChain chain,
                           JobRunner jobRunner, LedgerGate gate, AutoSpottingMetrics metrics,
                           AutoSpottingAuditWriter audit) {
        this(source, chain, jobRunner, gate, metrics, audit, Clock.systemUTC());
    }

    public GcpLedgerWriter(LedgerInputsSource source, LedgerRunChain chain, JobRunner jobRunner, LedgerGate gate,
                           AutoSpottingMetrics metrics, AutoSpottingAuditWriter audit, Clock clock) {
        super(GCP_JOB, GCP_PROVIDER, SavingsCalculator.GCP_VERSION, Provider.GCP, gate::gcpWriterOn, source, chain,
                jobRunner, gate, metrics, audit, clock);
    }
}
