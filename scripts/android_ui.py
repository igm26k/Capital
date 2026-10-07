"""Dedicated emulator UI support; app ANRs are never dismissed."""
import re
import subprocess
import xml.etree.ElementTree as ET

def dismiss_system_dialog(tree, device):
    nodes = list(tree.iter('node'))
    if not any(node.get('package') == 'android' and node.get('text') == "System UI isn't responding" for node in nodes):
        return False
    buttons = [node for node in nodes if node.get('package') == 'android' and node.get('text') == 'Close app' and node.get('enabled') == 'true']
    if len(buttons) != 1:
        raise RuntimeError('System UI dialog has no unique close action')
    x1, y1, x2, y2 = [int(v) for v in re.findall(r'\d+', buttons[0].attrib['bounds'])]
    assert x2 > x1 and y2 > y1
    device('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    return True

def read_screen(device, path):
    """Use only a fresh hierarchy; unavailable dumps cannot satisfy a UI assertion.

    The caller's existing deadline bounds retries after a transient startup dump.
    """
    try:
        device('shell', 'rm', '-f', path)
        device('shell', 'uiautomator', 'dump', path)
        tree = ET.fromstring(device('exec-out', 'cat', path))
        if tree.tag != 'hierarchy':
            return ET.Element('hierarchy')
    except (ET.ParseError, subprocess.CalledProcessError):
        return ET.Element('hierarchy')
    dismiss_system_dialog(tree, device)
    return tree
