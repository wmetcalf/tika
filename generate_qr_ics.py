import qrcode
import random
import math

# High-contrast premium rainbow colors (Material Design 800/900 palette)
RAINBOW_PALETTE = [
    "#C62828",  # Deep Red
    "#D84315",  # Deep Orange-Red
    "#EF6C00",  # Deep Orange
    "#F9A825",  # Deep Gold-Yellow
    "#2E7D32",  # Deep Green
    "#00695C",  # Deep Teal
    "#1565C0",  # Deep Blue
    "#283593",  # Deep Indigo
    "#6A1B9A",  # Deep Purple
    "#AD1457"   # Deep Magenta/Pink
]

def generate_ultimate_legacy_qr(mode="rainbow_radial", output_filename="rainbow_radial_qr.ics"):
    # 1. Generate the QR matrix for http://example.com/
    # Version 2 (25x25) with border=1 (yielding a 27x27 grid)
    qr = qrcode.QRCode(
        version=2,
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=1,
        border=1
    )
    qr.add_data("http://example.com/")
    qr.make(fit=True)
    matrix = qr.get_matrix()
    size = len(matrix)
    center = (size - 1) / 2.0
    
    random.seed(42)
    
    # 2. Build the HTML utilizing the Legacy HTML <font> tag & Fullwidth Unicode Alignment method
    # Dark modules use a single full block character '█' (U+2588) inside <font color="...">
    # Light modules use a single ideographic/fullwidth space '　' (U+3000)
    # This renders in vibrant colors in BOTH Outlook and Thunderbird!
    html_lines = []
    for r in range(size):
        line_parts = []
        for c in range(size):
            val = matrix[r][c]
            if val:
                # Choose color based on mode
                if mode == "crimson":
                    color = "#B71C1C"
                elif mode == "rainbow_gradient":
                    color = RAINBOW_PALETTE[(r + c) % len(RAINBOW_PALETTE)]
                elif mode == "rainbow_radial":
                    dist = math.sqrt((r - center) ** 2 + (c - center) ** 2)
                    color = RAINBOW_PALETTE[int(dist) % len(RAINBOW_PALETTE)]
                elif mode == "rainbow_random":
                    color = random.choice(RAINBOW_PALETTE)
                else:
                    color = "#000000"
                # Legacy HTML font color tag for 100% client styling preservation
                line_parts.append(f'<font color="{color}">█</font>')
            else:
                # Fullwidth space character (U+3000)
                line_parts.append('　')
                
        # Wrap each line in a div (which is kept as standard line structure)
        line_html = (
            f'<div style="line-height:12px;font-size:12px;margin:0;padding:0;'
            f'white-space:nowrap;font-family:\'Courier New\',Courier,monospace;">'
            f'{"".join(line_parts)}</div>'
        )
        html_lines.append(line_html)
        
    qr_html = "".join(html_lines)
    
    # Wrap in container div
    container_html = (
        f'<html><body><p style="font-family:sans-serif;font-size:14px;color:#333333;">Scan to verify ({mode.replace("_", " ").title()}):</p>'
        f'<div style="display:inline-block;background-color:#FFFFFF;padding:10px;'
        f'border:1px solid #DDDDDD;font-family:\'Courier New\',Courier,monospace;">'
        f'{qr_html}</div>'
        f'<p style="font-family:sans-serif;font-size:12px;color:#666666;">Expected URL: <a href="http://example.com/" style="color:#1A0DAB;">http://example.com/</a></p></body></html>'
    )
    
    # Escape semi-colons and commas for the ICS ALT-DESC parameter value
    escaped_html = container_html.replace(';', '\\;').replace(',', '\\,')
    
    # 3. Build plain-text description (Thunderbird/Lightning default fallback)
    plain_text_lines = []
    for row in matrix:
        line_chars = []
        for val in row:
            line_chars.append("█" if val else "　")
        plain_text_lines.append("".join(line_chars))
    escaped_plain_text_qr = "\\n".join(plain_text_lines)
    
    # 4. Construct ICS lines
    ics_lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        f"PRODID:-//Antigravity//Perfect Colored QR Legacy {mode.upper()}//EN",
        "BEGIN:VEVENT",
        f"UID:perfect-color-qr-legacy-{mode}-20260520@antigravity.google",
        "DTSTAMP:20260520T195000Z",
        "DTSTART:20260520T200000Z",
        "DTEND:20260520T210000Z",
        f"SUMMARY:Secure Verification QR ({mode.replace('_', ' ').title()})",
        f"DESCRIPTION:Verification QR Code.\\n\\n{escaped_plain_text_qr}",
        f"X-ALT-DESC;FMTTYPE=text/html:{escaped_html}",
        "END:VEVENT",
        "END:VCALENDAR"
    ]
    
    # 5. Fold lines as bytes directly
    folded_lines_bytes = []
    for line in ics_lines:
        folded_lines_bytes.append(fold_rfc5545_bytes(line))
        
    final_ics_bytes = b"\r\n".join(folded_lines_bytes) + b"\r\n"
    
    # Write directly to destination
    output_path = f"/tmp/{output_filename}"
    with open(output_path, "wb") as f:
        f.write(final_ics_bytes)
        
    print(f"Successfully generated legacy {mode} QR ICS at: {output_path}")

def fold_rfc5545_bytes(line_str):
    encoded = line_str.encode('utf-8')
    if len(encoded) <= 75:
        return encoded
        
    parts = []
    parts.append(encoded[:75])
    remaining = encoded[75:]
    
    while len(remaining) > 74:
        parts.append(b' ' + remaining[:74])
        remaining = remaining[74:]
        
    if remaining:
        parts.append(b' ' + remaining)
        
    return b'\r\n'.join(parts)

if __name__ == "__main__":
    generate_ultimate_legacy_qr("crimson", "crimson_qr.ics")
    generate_ultimate_legacy_qr("rainbow_gradient", "rainbow_gradient_qr.ics")
    generate_ultimate_legacy_qr("rainbow_radial", "rainbow_radial_qr.ics")
    generate_ultimate_legacy_qr("rainbow_random", "rainbow_random_qr.ics")
