package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeBatch;
import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.dto.BatchRequest;
import com.xammer.mediaops.dto.TranscodeJobRequest;
import com.xammer.mediaops.repository.TranscodeBatchRepository;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final TranscodeBatchRepository batchRepo;
    private final TranscodeJobRepository jobRepo;
    private final TranscodeJobService jobService;

    public BatchService(TranscodeBatchRepository batchRepo,
                        TranscodeJobRepository jobRepo,
                        TranscodeJobService jobService) {
        this.batchRepo = batchRepo;
        this.jobRepo = jobRepo;
        this.jobService = jobService;
    }

    /**
     * Creates a batch with individual jobs. If a sharedConfig is provided,
     * its values are merged into each job request (explicit job values override shared config).
     */
    @Transactional
    public TranscodeBatch createBatch(Long clientId, BatchRequest req) {
        TranscodeBatch batch = new TranscodeBatch();
        batch.setClientId(clientId);
        batch.setName(req.getName());
        batch.setTotalJobs(req.getJobs().size());
        batch = batchRepo.save(batch);

        for (TranscodeJobRequest jobReq : req.getJobs()) {
            if (req.getSharedConfig() != null) {
                mergeSharedConfig(jobReq, req.getSharedConfig());
            }
            TranscodeJob job = jobService.createJob(clientId, jobReq);
            job.setBatchId(batch.getId());
            jobRepo.save(job);
            jobService.processJobAsync(job.getId());
        }

        return batch;
    }

    /**
     * Gets a batch by ID, verifying client ownership.
     * Calculates and sets aggregate progress before returning.
     */
    @Transactional(readOnly = true)
    public TranscodeBatch getBatch(Long batchId, Long clientId) {
        TranscodeBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        if (!batch.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("Batch not found: " + batchId);
        }

        return batch;
    }

    /**
     * Calculates aggregate batch progress as the average of all job progresses.
     * Returns 0 if no jobs exist for the batch.
     */
    public int calculateAggregateProgress(Long batchId) {
        List<TranscodeJob> jobs = jobRepo.findByBatchId(batchId);
        if (jobs.isEmpty()) return 0;
        int totalProgress = jobs.stream().mapToInt(TranscodeJob::getProgress).sum();
        return totalProgress / jobs.size();
    }

    /**
     * Cancels a batch: sets all QUEUED and PROCESSING jobs to CANCELLED,
     * then marks the batch itself as CANCELLED.
     */
    @Transactional
    public void cancelBatch(Long batchId, Long clientId) {
        TranscodeBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        if (!batch.getClientId().equals(clientId)) {
            throw new IllegalArgumentException("Batch not found: " + batchId);
        }

        List<TranscodeJob> jobs = jobRepo.findByBatchId(batchId);
        for (TranscodeJob job : jobs) {
            if (job.getStatus() == TranscodeJob.JobStatus.QUEUED
                    || job.getStatus() == TranscodeJob.JobStatus.PROCESSING) {
                job.setStatus(TranscodeJob.JobStatus.CANCELLED);
                jobRepo.save(job);
            }
        }

        batch.setStatus(TranscodeBatch.BatchStatus.CANCELLED);
        batchRepo.save(batch);
    }

    /**
     * Lists all batches for a client, ordered by most recently created first.
     */
    public List<TranscodeBatch> listBatches(Long clientId) {
        return batchRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    /**
     * Merges shared config values into an individual job request.
     * Explicit values in the job request take precedence over shared config.
     * This follows the same pattern as PresetService.applyPreset().
     */
    private void mergeSharedConfig(TranscodeJobRequest jobReq, TranscodeJobRequest sharedConfig) {
        if (isNullOrEmpty(jobReq.getOutputFormat()) && !isNullOrEmpty(sharedConfig.getOutputFormat())) {
            jobReq.setOutputFormat(sharedConfig.getOutputFormat());
        }
        if (isNullOrEmpty(jobReq.getResolution()) && !isNullOrEmpty(sharedConfig.getResolution())) {
            jobReq.setResolution(sharedConfig.getResolution());
        }
        if (isNullOrEmpty(jobReq.getBitrate()) && !isNullOrEmpty(sharedConfig.getBitrate())) {
            jobReq.setBitrate(sharedConfig.getBitrate());
        }
        if (jobReq.getFps() == null && sharedConfig.getFps() != null) {
            jobReq.setFps(sharedConfig.getFps());
        }
        if (isNullOrEmpty(jobReq.getCodec()) && !isNullOrEmpty(sharedConfig.getCodec())) {
            jobReq.setCodec(sharedConfig.getCodec());
        }
        if (isNullOrEmpty(jobReq.getPriority()) && !isNullOrEmpty(sharedConfig.getPriority())) {
            jobReq.setPriority(sharedConfig.getPriority());
        }
        if (isNullOrEmpty(jobReq.getAudioBitrate()) && !isNullOrEmpty(sharedConfig.getAudioBitrate())) {
            jobReq.setAudioBitrate(sharedConfig.getAudioBitrate());
        }
        if (jobReq.getAudioSampleRate() == null && sharedConfig.getAudioSampleRate() != null) {
            jobReq.setAudioSampleRate(sharedConfig.getAudioSampleRate());
        }
        if (jobReq.getAudioChannels() == null && sharedConfig.getAudioChannels() != null) {
            jobReq.setAudioChannels(sharedConfig.getAudioChannels());
        }
        if (isNullOrEmpty(jobReq.getCustomFfmpegParams()) && !isNullOrEmpty(sharedConfig.getCustomFfmpegParams())) {
            jobReq.setCustomFfmpegParams(sharedConfig.getCustomFfmpegParams());
        }
    }

    private boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
