CREATE TABLE IF NOT EXISTS exam_template_sections (
    exam_id    BIGINT NOT NULL,
    section_id BIGINT NOT NULL,
    CONSTRAINT pk_exam_template_sections PRIMARY KEY (exam_id, section_id),
    CONSTRAINT fk_ets_exam    FOREIGN KEY (exam_id)    REFERENCES exams(id)             ON DELETE CASCADE,
    CONSTRAINT fk_ets_section FOREIGN KEY (section_id) REFERENCES template_sections(id)
);
