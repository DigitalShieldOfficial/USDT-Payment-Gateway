# Security Policy

## Reporting a Vulnerability

If you discover a potential security vulnerability in this repository, please
contact Digital Shield privately at **service@ds.pro** with the subject
`[Security] USDT-Payment-Gateway`.

If you do not receive a reply, join our official technical support group at
https://t.me/+746hER7mf-82MWFl and contact the group owner to follow up on your
email. Share only that you are following up on a private security report;
do not post vulnerability details, exploit code, credentials, or customer data
in the group. Arrange a private communication channel with the group owner.

Please report potential vulnerabilities privately first and coordinate any
public disclosure as described in "Responsible Testing and Disclosure" below.
Use public issues for ordinary bugs and integration questions that do not
contain sensitive information.

Include the following information when available:

- The affected commit or version, Demo language, and runtime version.
- A description of the vulnerability and its potential impact.
- Minimal reproduction steps or a proof of concept using dummy credentials.
- Relevant logs with secrets and personal information removed.
- Any suggested mitigation or fix.

Do not send real API secrets, private keys, seed phrases, payment credentials,
or customer data. If sensitive evidence is necessary, ask for an appropriate
transfer method first.

## Scope and Maintenance

This policy covers the documentation, integration examples, and Demo code in
this repository, including signing, webhook verification, and handling of
sensitive information.

Security maintenance covers the following versions:

| Branch or version | Security maintenance |
| --- | --- |
| Latest `main` revision | Supported |
| Latest published release tag | Supported |
| Older tags and historical revisions | Not maintained |

The latest release tag means the tag identified by the maintainers as the
latest release, not the tag with the most recent Git creation date. Until a
release tag is published, security maintenance applies to `main` only.

Please reproduce issues on a supported version where possible. Reports about
older revisions or modified forks should identify the revision and relevant
changes; backports to unsupported versions are not promised.

Compatibility with older language runtimes does not mean that Digital
Shield maintains those runtimes or supplies security patches for them. Use
maintained, patched runtimes and dependencies for production deployments.

Vulnerabilities in third-party runtimes or libraries should also be reported
to their maintainers. If the repository's use of a dependency contributes to
the issue, explain that integration in your report.

For suspected vulnerabilities in hosted DSPay services, contact the same email
address and identify the affected service. This repository's code license does
not authorize testing of hosted services or third-party deployments.

## Responsible Testing and Disclosure

Test in local or otherwise explicitly authorized environments. Do not access
other users' data, disrupt services, or create real payments or transfers solely
to demonstrate a vulnerability without authorization.

Reports will be assessed based on their reproducibility and impact. Please
report privately first and work with the maintainers to agree on a reasonable
disclosure timeline so affected users can receive fixes or mitigations. If email
receives no response, use the follow-up channel described above. This policy
does not require an indefinite delay in disclosure and does not promise a response or remediation deadline,
support for every historical version, or a monetary reward.

## Demo Limitations

The Demos illustrate integration and callback acknowledgement; they are not a
complete production merchant system. Merchants remain responsible for durable
order state, idempotent accounting and fulfillment, access controls, secret
management, and deployment security. A successful callback verification or
browser redirect alone must not be treated as proof that fulfillment occurred.
