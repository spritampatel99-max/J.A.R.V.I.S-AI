# actions/contacts.py
# Simple local contact book: name -> phone number (E.164 format, e.g.
# +14155552671). Used so messaging/calling can target a phone number
# directly via wa.me/tel: links instead of blindly typing a name into a
# search box and hoping the right contact gets selected.

import json
import sys
import difflib
from pathlib import Path


def _get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


CONTACTS_PATH = _get_base_dir() / "config" / "contacts.json"


def load_contacts() -> dict:
    if not CONTACTS_PATH.exists():
        return {}
    try:
        return json.loads(CONTACTS_PATH.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"[Contacts] ⚠️ Could not read contacts.json: {e}")
        return {}


def save_contact(name: str, phone: str) -> str:
    contacts = load_contacts()
    contacts[name.strip()] = phone.strip()
    CONTACTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONTACTS_PATH.write_text(json.dumps(contacts, indent=2, ensure_ascii=False), encoding="utf-8")
    return f"Saved contact: {name} -> {phone}"


def find_number(name: str) -> str | None:
    """Fuzzy-matches `name` against saved contacts, returns phone or None."""
    contacts = load_contacts()
    if not contacts:
        return None

    key = name.strip().lower()
    for cname, phone in contacts.items():
        if cname.strip().lower() == key:
            return phone

    names   = list(contacts.keys())
    matches = difflib.get_close_matches(name, names, n=1, cutoff=0.6)
    if matches:
        return contacts[matches[0]]

    for cname, phone in contacts.items():
        if key in cname.lower() or cname.lower() in key:
            return phone

    return None
