# Batch OCR tools

The manual workflow in .github/workflows/process-voter-pdfs.yml processes PDFs in voter-pdfs and uploads OCR results as a short-lived GitHub Actions artifact. It creates JSON/CSV records and a printable HTML sheet. OCR results must be reviewed against the source PDFs. Family suggestions require explicit name-to-relative-name matches; a shared house number alone is not enough.

Privacy: electoral rolls contain personal information. Make this repository Private before running the OCR workflow.