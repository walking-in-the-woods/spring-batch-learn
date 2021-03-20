CREATE SEQUENCE customer_seq;

CREATE TABLE customer (
  id integer check (id > 0) NOT NULL default nextval ('customer_seq'),
  firstName varchar(255) default NULL,
  lastName varchar(255) default NULL,
  birthdate varchar(255),
  PRIMARY KEY (id)
) ;

ALTER SEQUENCE customer_seq RESTART WITH 1;