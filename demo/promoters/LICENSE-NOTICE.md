# Data attribution

`tasks.jsonl` contains 150 sequences (75 promoter / 75 non-promoter, 251 bp each) sampled deterministically
(seed 20261017) from the **test** split of the `human_nontata_promoters` dataset of **Genomic Benchmarks**,
retrieved via the Hugging Face datasets-server API
(`katarinagresova/Genomic_Benchmarks_human_nontata_promoters`). Label 1 was mapped to `promoter`, label 0 to `non_promoter`.

- Source: https://github.com/ML-Bioinfo-CEITEC/genomic_benchmarks
- License: Apache License 2.0
- Citation: Grešová K., Martinek V., Čechák D., Šimeček P., Alexiou P. (2023).
  *Genomic benchmarks: a collection of datasets for genomic sequence classification.*
  BMC Genomic Data 24:25. https://doi.org/10.1186/s12863-023-01123-8

Only the file format was changed (JSONL rows with `category`, `input.sequence`, `expected.label`); sequences are unmodified.
