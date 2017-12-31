USE trace
;
DROP TABLE IF EXISTS Regression
;
CREATE TABLE Regression
(
	regression_id INTEGER NOT NULL AUTO_INCREMENT,
	buggy_trace INTEGER,
	correct_trace INTEGER,
	root_cause_step INTEGER,
	is_overskip INTEGER,
	over_skip_number INTEGER,
	PRIMARY KEY (regression_id)
) 
;

