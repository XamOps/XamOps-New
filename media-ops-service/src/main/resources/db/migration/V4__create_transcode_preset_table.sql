-- Create TranscodePreset table for reusable transcoding configurations
CREATE TABLE media_transcode_preset (
    id BIGINT PRIMARY KEY,
    client_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    output_format VARCHAR(20),
    resolution VARCHAR(20),
    bitrate VARCHAR(30),
    fps INTEGER,
    codec VARCHAR(20),
    audio_bitrate VARCHAR(20),
    custom_ffmpeg_params TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP
);

CREATE SEQUENCE media_transcode_preset_id_seq START 1 INCREMENT 1;
