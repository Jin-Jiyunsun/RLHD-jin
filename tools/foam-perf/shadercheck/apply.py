"""Apply a list of exact replacements to a file in the worktree. Usage: apply.py <group module>"""
import importlib, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
ROOT = r'C:\Users\Jin\IdeaProjects\RLHD-jin-shore-foam'

group = importlib.import_module(sys.argv[1])
for rel, pairs in group.edits.items():
    path = os.path.join(ROOT, rel)
    text = open(path, encoding='utf-8', newline='').read()
    crlf = '\r\n' in text
    if crlf:
        text = text.replace('\r\n', '\n')
    for old, new in pairs:
        count = text.count(old)
        if count != 1:
            sys.exit(f'{rel}: expected 1 match, found {count} for:\n{old[:200]}')
        text = text.replace(old, new)
    if crlf:
        text = text.replace('\n', '\r\n')
    open(path, 'w', encoding='utf-8', newline='').write(text)
    print(f'{rel}: {len(pairs)} edits')
