from public_source import scan

if __name__ == "__main__":
    files, findings = scan()
    # Report locations and rule names only, never suspected credential values.
    for path, line, rule in findings:
        print(f"{path}:{line}: {rule}")
    print(f"Public source: {len(files)} files; {len(findings)} findings.")
    raise SystemExit(1 if findings else 0)
