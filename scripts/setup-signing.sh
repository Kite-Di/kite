#!/usr/bin/env bash
# Sets up everything needed to sign Kite's releases for Maven Central:
# generates a fresh GPG key, publishes the public half, exports the keyring
# Gradle signs with, and writes the credentials into ~/.gradle/gradle.properties.
#
# Nothing is pasted by hand — the 6000-character key blob never touches the
# clipboard, which is where the in-memory approach kept going wrong.
#
# Run it, answer three prompts, then: ./gradlew publishToMavenLocal
set -euo pipefail

GRADLE_PROPS="$HOME/.gradle/gradle.properties"
KEYRING="$HOME/.gnupg/kite-secring.gpg"

command -v gpg >/dev/null || { echo "gpg not found — run: brew install gnupg" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 1; }

# pinentry needs a terminal; without this gpg fails with "Inappropriate ioctl for device".
export GPG_TTY="$(tty || true)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"

echo "=== 1/6  Who the key belongs to ==="
echo "This goes into the key's user id, which everyone verifying a release sees."

# Backspacing over a non-ASCII character in a terminal can delete one byte of a
# multi-byte sequence, leaving a stray byte in the middle of the name. gpg keeps
# it verbatim, and the uid is public and effectively permanent — so check first.
check_text() {
  printf '%s' "$2" | python3 -c "
import sys
raw = sys.stdin.buffer.read()
try:
    s = raw.decode('utf-8')
except UnicodeDecodeError:
    print('  $1 contains bytes that are not valid UTF-8 — probably a half-deleted character.')
    sys.exit(1)
if any(ord(c) < 32 for c in s):
    print('  $1 contains a control character.')
    sys.exit(1)
"
}

while :; do
  read -r -p "Name (as it should appear in the key): " NAME
  read -r -p "Email: " EMAIL
  if [ -z "$NAME" ] || [ -z "$EMAIL" ]; then
    echo "  Both are required."
    continue
  fi
  check_text "The name" "$NAME" || continue
  check_text "The email" "$EMAIL" || continue
  echo
  echo "  The key will be issued to:  $NAME <$EMAIL>"
  read -r -p "  Correct? [y/N] " ok
  case "$ok" in [yY]*) break ;; esac
  echo
done

echo
echo "=== 2/6  Passphrase ==="
echo "Typed blind. It protects the private key on this machine and will be"
echo "written to $GRADLE_PROPS (file mode 600)."
read -r -s -p "Passphrase: " PW1; echo
read -r -s -p "Again: " PW2; echo
[ "$PW1" = "$PW2" ] || { echo "They differ." >&2; exit 1; }
[ -n "$PW1" ] || { echo "Empty passphrase — allowed by gpg, but then leave signing.password empty too." >&2; }
printf '%s' "$PW1" > "$work/pw"
chmod 600 "$work/pw"
unset PW2

echo
echo "=== 3/6  Generating an RSA 4096 key (no expiry) ==="
# The fingerprint comes from the generation itself, not from a later lookup by
# email: an older key with the same address would win that lookup.
gen="$(gpg --batch --pinentry-mode loopback --passphrase-file "$work/pw" --status-fd 1 \
       --quick-generate-key "$NAME <$EMAIL>" rsa4096 default 0)"
FPR="$(printf '%s\n' "$gen" | awk '/^\[GNUPG:\] KEY_CREATED/ {print $4; exit}')"
[ -n "$FPR" ] || { echo "Could not read the new key's fingerprint from:" >&2; printf '%s\n' "$gen" >&2; exit 1; }
KEYID="${FPR: -8}"
echo "fingerprint: $FPR"
echo "key id     : $KEYID"

echo
echo "=== 4/6  Publishing the public key ==="
# Central looks the key up on a public keyserver to verify signatures. Two of
# them, because keys.openpgp.org withholds the uid until the email is confirmed.
for ks in keys.openpgp.org keyserver.ubuntu.com; do
  if gpg --keyserver "$ks" --send-keys "$FPR" 2>/dev/null; then
    echo "  sent to $ks"
  else
    echo "  WARNING: could not reach $ks — retry later with:"
    echo "           gpg --keyserver $ks --send-keys $FPR"
  fi
done

echo
echo "=== 5/6  Exporting the keyring Gradle signs with ==="
# Binary, not armored: this is what signing.secretKeyRingFile expects.
gpg --batch --pinentry-mode loopback --passphrase-file "$work/pw" \
    --export-secret-keys "$KEYID" > "$KEYRING"
chmod 600 "$KEYRING"
[ -s "$KEYRING" ] || { echo "Export produced an empty file." >&2; exit 1; }
echo "  $KEYRING ($(wc -c < "$KEYRING" | tr -d ' ') bytes)"

# Prove the passphrase really opens this key, with a cold agent so nothing is
# answered from cache. This is the check that would have caught the earlier mess.
gpg-connect-agent reloadagent /bye >/dev/null 2>&1 || true
if gpg --batch --pinentry-mode loopback --passphrase-file "$work/pw" \
       --export-secret-keys "$KEYID" >/dev/null 2>&1; then
  echo "  passphrase verified against the key"
else
  echo "ERROR: the passphrase does not open the key that was just created." >&2
  exit 1
fi

echo
echo "=== 6/6  Writing $GRADLE_PROPS ==="
mkdir -p "$(dirname "$GRADLE_PROPS")"
[ -f "$GRADLE_PROPS" ] && cp "$GRADLE_PROPS" "$GRADLE_PROPS.bak.$(date +%s)"

PW_FILE="$work/pw" KEYID="$KEYID" KEYRING="$KEYRING" GRADLE_PROPS="$GRADLE_PROPS" python3 <<'PY'
import os, re

props = os.environ["GRADLE_PROPS"]
passphrase = open(os.environ["PW_FILE"]).read()

def escape(value):
    """Java .properties escaping: backslashes, and a leading space that would be stripped."""
    value = value.replace("\\", "\\\\")
    if value.startswith(" "):
        value = "\\" + value
    return value

text = open(props).read() if os.path.exists(props) else ""
# Drop every previous signing setting, commented ones included, so the two
# mechanisms can't both be present — in-memory wins over the keyring silently.
text = "\n".join(
    line for line in text.splitlines()
    if not re.match(r"\s*#?\s*(signingInMemory\w*|signing\.(keyId|password|secretKeyRingFile))\s*=", line)
)
text = text.rstrip("\n")

block = "\n".join([
    "",
    "# Release signing. Written by scripts/setup-signing.sh — do not hand-edit.",
    f"signing.keyId={os.environ['KEYID']}",
    f"signing.password={escape(passphrase)}",
    f"signing.secretKeyRingFile={os.environ['KEYRING']}",
    "",
])

with open(props, "w") as f:
    f.write(text + "\n" + block)
os.chmod(props, 0o600)

has_user = re.search(r"^mavenCentralUsername=\S", text, re.M)
has_pass = re.search(r"^mavenCentralPassword=\S", text, re.M)
print("  signing.* written, mode 600")
print("  Central token:", "present" if (has_user and has_pass) else "MISSING — add mavenCentralUsername / mavenCentralPassword")
PY

echo
echo "Done."
echo
echo "  Key $KEYID is ready and Gradle is pointed at it."
echo "  Confirm the email from keys.openpgp.org so the key carries your uid."
echo
echo "  Next:  ./gradlew publishToMavenLocal      # .asc files should appear"
echo "         ./gradlew publishToMavenCentral"
echo "         ./gradlew -p kite/gradle-plugin publishToMavenCentral"
