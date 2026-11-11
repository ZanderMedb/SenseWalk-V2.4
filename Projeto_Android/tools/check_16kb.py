"""Verifica LOAD/RELRO ELF e alinhamento ZIP das bibliotecas Android de 64 bits.

Uso: python3 tools/check_16kb.py app/build/outputs/apk/debug/app-debug.apk
Sem dependências externas. Complementar com zipalign -c -P 16 4 APK.
"""

import argparse
import struct
import zipfile

PAGE_SIZE = 16384
ABIS = {"arm64-v8a", "x86_64"}


def check(apk):
    failures = []
    checked = 0
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
        for info in archive.infolist():
            parts = info.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or parts[1] not in ABIS or not parts[2].endswith(".so"):
                continue
            checked += 1
            data = archive.read(info)
            if data[:5] != b"\x7fELF\x02" or data[5] not in (1, 2):
                failures.append(f"{info.filename}: ELF64 inválido")
                continue
            endian = "<" if data[5] == 1 else ">"
            phoff = struct.unpack_from(endian + "Q", data, 32)[0]
            size, count = struct.unpack_from(endian + "HH", data, 54)
            headers = [struct.unpack_from(endian + "IIQQQQQQ", data, phoff + index * size) for index in range(count)]
            loads = [header for header in headers if header[0] == 1]
            errors = []
            if not loads or any(h[7] < PAGE_SIZE or (h[3] - h[2]) % PAGE_SIZE for h in loads):
                errors.append("segmento LOAD não alinhado a 16 KB")
            for relro in (header for header in headers if header[0] == 0x6474E552):
                end = relro[3] + relro[6]
                if end % PAGE_SIZE and any(
                    h[1] & 2 and h[3] <= relro[3] and end < h[3] + h[6]
                    for h in loads
                ):
                    errors.append("RELRO divide página de 16 KB com dados graváveis")
            if info.compress_type == zipfile.ZIP_STORED:
                raw.seek(info.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
                offset = info.header_offset + 30 + name_size + extra_size
                if offset % PAGE_SIZE:
                    errors.append("biblioteca não comprimida desalinhada no ZIP")
            print(f"{'FAIL' if errors else 'OK'} {info.filename}")
            failures.extend(f"{info.filename}: {error}" for error in errors)
    if not checked:
        failures.append("nenhuma biblioteca de 64 bits encontrada")
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"Verificadas {checked} bibliotecas de 64 bits: LOAD, RELRO e ZIP compatíveis com 16 KB.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    check(parser.parse_args().apk)
