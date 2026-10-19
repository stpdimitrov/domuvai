-- AUTH-05d (#170) — what is entered for a unit belongs to the entrance the unit lies in (PM-ORG-001, ADR-005).
-- Each table below names both; until now only the service's writes kept them together. The database refuses a row
-- whose unit lies in another entrance: a title there would derive a role in the wrong entrance (ADR-002).
ALTER TABLE registry.unit ADD CONSTRAINT unit_id_entrance UNIQUE (id, entrance_id);

ALTER TABLE registry.title
  ADD CONSTRAINT title_unit_in_entrance FOREIGN KEY (unit_id, entrance_id) REFERENCES registry.unit (id, entrance_id);
ALTER TABLE registry.household_member
  ADD CONSTRAINT household_member_unit_in_entrance FOREIGN KEY (unit_id, entrance_id) REFERENCES registry.unit (id, entrance_id);
ALTER TABLE registry.animal
  ADD CONSTRAINT animal_unit_in_entrance FOREIGN KEY (unit_id, entrance_id) REFERENCES registry.unit (id, entrance_id);
ALTER TABLE registry.absence_declaration
  ADD CONSTRAINT absence_declaration_unit_in_entrance FOREIGN KEY (unit_id, entrance_id) REFERENCES registry.unit (id, entrance_id);
ALTER TABLE registry.book_declaration
  ADD CONSTRAINT book_declaration_unit_in_entrance FOREIGN KEY (unit_id, entrance_id) REFERENCES registry.unit (id, entrance_id);
