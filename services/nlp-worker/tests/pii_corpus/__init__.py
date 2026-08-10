"""Measurement corpus for the PII shield.

Two rates, always reported together:

* **leak rate** -- a person's name the shield should have redacted and did not.
* **false-redaction rate** -- a product, company, feature or role name it redacted anyway.

Measuring only the first is how a redaction bug gets "fixed" by redacting everything, and this
product summarises meetings *about named software*: a shield that eats `SAP`, `Protheus` and `RM`
produces summaries nobody can read. Neither number means anything without the other.
"""
