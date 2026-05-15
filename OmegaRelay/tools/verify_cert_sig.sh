#!/usr/bin/env bash
# Manually verify that <child.der>'s signature was made by <parent.der>'s
# public key. openssl `verify` gets confused when subject == issuer DN, even
# when the signing key is genuinely different — exactly Android's situation
# where every keystore-attested leaf has subject="CN=Android Keystore Key".
#
# Usage:  ./verify_cert_sig.sh child.der parent.der

set -euo pipefail

child=$1
parent=$2
tmpdir=$(mktemp -d)
trap "rm -rf $tmpdir" EXIT

# Pull the to-be-signed portion + signature out of the child.
openssl asn1parse -in "$child" -inform DER -strparse 4 -out /dev/null > "$tmpdir/child.dump" 2>&1
# Easier: use openssl x509 -outform DER and slice manually... actually
# the simplest correct approach is to use Java/BC. Skip openssl-only.
echo "openssl can't do this cleanly without scripting ASN.1 parsing; use the Kotlin verifier."
EOF
