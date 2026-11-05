"""Genera la clave de firma del APK a partir de una frase secreta.

La frase vive solo en los secretos de GitHub (CLAVE_FIRMA). Con la misma frase se obtiene
siempre exactamente la misma clave y el mismo certificado, así cada APK nuevo se instala
encima del anterior. La clave nunca se guarda en el repositorio.

Uso: CLAVE_FIRMA=... python3 derive_signing_key.py salida.p12 contraseña_del_archivo
"""

import datetime
import hashlib
import math
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

DOMAIN = b"iacamara-firma-apk-v1|"
SMALL_PRIMES = [p for p in range(3, 2000) if all(p % d for d in range(2, int(p**0.5) + 1))]


class DeterministicStream:
    """Bytes pseudoaleatorios deterministas (SHA-256 en modo contador)."""

    def __init__(self, seed: bytes):
        self.seed = seed
        self.counter = 0

    def read(self, n: int) -> bytes:
        out = b""
        while len(out) < n:
            out += hashlib.sha256(self.seed + self.counter.to_bytes(8, "big")).digest()
            self.counter += 1
        return out[:n]


def is_probable_prime(n: int) -> bool:
    for p in SMALL_PRIMES:
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for a in SMALL_PRIMES[:40]:
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def generate_prime(stream: DeterministicStream, bits: int) -> int:
    while True:
        candidate = int.from_bytes(stream.read(bits // 8), "big")
        candidate |= (1 << (bits - 1)) | (1 << (bits - 2)) | 1
        if is_probable_prime(candidate):
            return candidate


def derive_key(secret: bytes, bits: int = 2048) -> rsa.RSAPrivateKey:
    stream = DeterministicStream(hashlib.sha256(DOMAIN + secret).digest())
    e = 65537
    while True:
        p = generate_prime(stream, bits // 2)
        q = generate_prime(stream, bits // 2)
        if p == q:
            continue
        phi = (p - 1) * (q - 1)
        if math.gcd(e, phi) == 1:
            break
    d = pow(e, -1, phi)
    numbers = rsa.RSAPrivateNumbers(
        p=p,
        q=q,
        d=d,
        dmp1=rsa.rsa_crt_dmp1(d, p),
        dmq1=rsa.rsa_crt_dmq1(d, q),
        iqmp=rsa.rsa_crt_iqmp(p, q),
        public_numbers=rsa.RSAPublicNumbers(e, p * q),
    )
    return numbers.private_key()


def build_certificate(key: rsa.RSAPrivateKey) -> x509.Certificate:
    # Todos los campos son fijos y la firma RSA PKCS#1 v1.5 es determinista:
    # el certificado sale idéntico en cada compilación.
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "IA Camara")])
    return (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(20260101)
        .not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc))
        .not_valid_after(datetime.datetime(2076, 1, 1, tzinfo=datetime.timezone.utc))
        .sign(key, hashes.SHA256())
    )


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("Uso: derive_signing_key.py salida.p12 contraseña_del_archivo")
    secret = os.environ.get("CLAVE_FIRMA", "").encode("utf-8")
    if len(secret) < 12:
        sys.exit("CLAVE_FIRMA debe tener al menos 12 caracteres.")
    out_path, file_password = sys.argv[1], sys.argv[2].encode("utf-8")

    key = derive_key(secret)
    cert = build_certificate(key)
    data = pkcs12.serialize_key_and_certificates(
        name=b"iacamara",
        key=key,
        cert=cert,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(file_password),
    )
    with open(out_path, "wb") as f:
        f.write(data)
    fingerprint = cert.fingerprint(hashes.SHA256()).hex(":").upper()
    print(f"Certificado de firma SHA-256: {fingerprint}")


if __name__ == "__main__":
    main()
