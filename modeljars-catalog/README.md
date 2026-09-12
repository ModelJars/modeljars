# ModelJars generated catalog

This single Gradle module replaces per-model source modules. Its aggregate classpath resource,
individual marker JARs, Maven publications, and the website catalog are generated from
`catalog/models.json`. Qualified virtual-model recipes are declared separately in
`catalog/compositions.json`; the aggregate and remote CLI catalogs project them as discoverable
hybrids without treating a composition as a downloadable set of weights.
