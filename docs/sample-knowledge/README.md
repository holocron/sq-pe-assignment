# Sample knowledge-base corpus

Three policy documents covering AML thresholds, sanctioned jurisdictions and crypto/virtual-asset
risk. Their content is deliberately aligned with the thresholds encoded in the seeded `risk_rules`,
so `search_policy_knowledge` returns text that actually justifies what the deterministic engine
computes.

| File | Format | Sections | Chunks when ingested |
|---|---|---|---|
| `AML-Thresholds-and-Structuring-Policy.docx` | Word, real `Heading 1`/`Heading 2` styles | 11 | 11 |
| `Cryptocurrency-and-Virtual-Asset-Risk-Policy.docx` | Word, real heading styles | 11 | 11 |
| `Sanctions-and-High-Risk-Jurisdictions-Policy.pdf` | PDF, headings only distinguishable typographically | 10 | 10 |

Regenerate them with `scripts/generate-knowledge-docs.py`.

## These files are shipped inside the application

The same three files also live at `backend/src/main/resources/knowledge/`, which is what
`KnowledgeBootstrap` reads at startup to seed an empty knowledge base. They are on the classpath
rather than resolved relative to the working directory so that `java -jar` behaves identically from
any directory, and so the corpus travels with the artifact.

**After regenerating the documents, copy them across:**

```sh
cp docs/sample-knowledge/*.docx docs/sample-knowledge/*.pdf \
   backend/src/main/resources/knowledge/
```

`DocumentTextExtractorTest.ShippedCorpus` parses the shipped copies, so a stale or corrupt copy
fails the build rather than degrading the knowledge base silently.

## Seeding behaviour

Seeding runs once, on `ApplicationReadyEvent`, on a background daemon thread, and only when the
corpus holds no document that is anything other than `FAILED`. Switch it off with:

```yaml
caa:
  knowledge:
    bootstrap:
      enabled: false            # default true
      location: classpath*:/knowledge/*   # accepts file: patterns too
      uploaded-by: system       # knowledge_documents.uploaded_by on seeded rows
```
