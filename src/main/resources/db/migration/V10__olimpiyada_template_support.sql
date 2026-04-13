-- Add template type to distinguish Olimpiyada templates from standard ones
ALTER TABLE templates ADD COLUMN IF NOT EXISTS template_type VARCHAR(50) DEFAULT 'STANDARD';

-- Add point groups JSON to template sections (e.g. [{"from":1,"to":15,"points":1.0},{"from":16,"to":20,"points":1.5}])
ALTER TABLE template_sections ADD COLUMN IF NOT EXISTS point_groups TEXT;
