-- AvNB_uwa_6n6 is the JobTech taxonomy code for Stockholm municipality and
-- apaJ_2ja_LuF the code for the Data/IT occupation field. Both confirmed against
-- the live taxonomy API during research.
INSERT INTO search_criteria (name, query, municipality_codes, municipality_names, occupation_field_codes, enabled)
VALUES
    ('Java/systemutvecklare Stockholm',
     'java systemutvecklare backend fullstack',
     'AvNB_uwa_6n6',
     'Stockholm',
     'apaJ_2ja_LuF',
     TRUE),
    ('IT-jobb hela Sverige',
     'utvecklare developer engineer',
     '',
     '',
     'apaJ_2ja_LuF',
     TRUE);
