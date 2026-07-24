# Test data

Keep stable editor fixtures here and mirror the production feature package in each path.

Use readable before/after pairs for source-changing behavior and inline highlighting markup for Inspections. Tests that only need generated or
temporary source should use `HikageCodeInsightTestCase.configureKotlinByText` instead of creating disposable fixture files.