import requests
import gspread
from google.oauth2.service_account import Credentials
import os
import json
import sys
import traceback
from datetime import datetime, timedelta, timezone

# Constants
SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
SOCRATA_ENDPOINT = 'https://data.nola.gov/resource/gjzc-adg8.json'
# Filtering by bounds rather than neighborhood since 'neighborhood' column doesn't exist
# We will use the same bounding box filtering as the frontend does:
# Bounds approx: North: 29.98, West: -90.03, South: 29.95, East: -89.98 for Lower Ninth
BOUNDS = "within_box(the_geom, 29.98, -90.03, 29.95, -89.98)"
SELECT_FIELDS = (
    "geoaddress AS address, prevhearingresult AS status, casefiled AS notice_date, "
    "the_geom AS location, caseno AS casenumber, geopin, initinspection, "
    "permittype AS permit_type, permitstatus AS permit_status, permitfiling AS permit_filing, "
    "nexthearingdate AS next_hearing, stage, o_c, zipcode"
)
QUERY = f"?$select={SELECT_FIELDS}&$where={BOUNDS} AND prevhearingresult IN('Guilty', 'Uncommitted')&$limit=50000"

def parse_socrata_date(date_str):
    if not date_str:
        return None
    try:
        if 'T' in date_str:
            date_part = date_str.split('T')[0]
            return datetime.strptime(date_part, '%Y-%m-%d')
        elif '-' in date_str:
            return datetime.strptime(date_str, '%Y-%m-%d')
        elif len(date_str) >= 8:
            return datetime.strptime(date_str[:8], '%Y%m%d')
        return None
    except Exception as e:
        print(f"Failed to parse date {date_str}: {e}")
        return None


def _parse_socrata_date_str(date_str):
    d = parse_socrata_date(date_str)
    return d.strftime("%m/%d/%Y") if d else ""

def main():
    print("Fetching data from NOLA Socrata API...")
    url = SOCRATA_ENDPOINT + QUERY
    response = requests.get(url)

    if response.status_code != 200:
        print(f"Error fetching data: {response.status_code} {response.text}")
        return

    data = response.json()
    print(f"Retrieved {len(data)} records.")

    processed_data = []
    # Add Headers (A-T are owned by this script and rewritten on every run.
    # Columns U+ are owned by enrich_properties.py and classify_graffiti.py
    # and are preserved across runs because batch_clear scopes A:T only.)
    processed_data.append([
        "Address",                # A
        "Neighborhood",           # B
        "Name/Type",              # C
        "Features & 2026 Status", # D (Status)
        "Previous Statuses",      # E
        "Updated on",             # F
        "Case Number",            # G
        "Notice Date",            # H
        "Deadline",               # I
        "Latitude",               # J
        "Longitude",              # K
        "geopin",                 # L
        "init_inspection",        # M
        "permit_type",            # N
        "permit_status",          # O
        "permit_filing",          # P
        "next_hearing",           # Q
        "stage",                  # R
        "o_c",                    # S
        "zipcode",                # T
    ])

    seen_cases = set()
    current_date = datetime.now(timezone.utc).strftime("%m/%d/%Y %H:%M:%S UTC")

    for row in data:
        caseno = row.get("casenumber")
        if not caseno or caseno in seen_cases:
            continue

        location = row.get("location")
        if not location or "coordinates" not in location:
            continue # Skip missing location

        lng = location["coordinates"][0]
        lat = location["coordinates"][1]

        address = row.get("address", "Unknown")
        status = row.get("status", "")
        if 'guilty' in status.lower():
            status = 'Guilty'
        else:
            status = 'Uncommitted'

        notice_date_str = row.get("notice_date")
        notice_date_obj = parse_socrata_date(notice_date_str)

        if notice_date_obj:
            notice_date = notice_date_obj.strftime("%m/%d/%Y")
            deadline_obj = notice_date_obj + timedelta(days=30)
            deadline = deadline_obj.strftime("%m/%d/%Y")
        else:
            notice_date = ""
            deadline = ""

        processed_data.append([
            address,                                          # A: Address
            "Lower Ninth Ward",                               # B: Neighborhood
            "",                                               # C: Name/Type (blank for now)
            status,                                           # D: Features & 2026 Status (Status)
            "",                                               # E: Previous Statuses (blank for now)
            current_date,                                     # F: Updated on
            caseno,                                           # G: Case Number
            notice_date,                                      # H: Notice Date
            deadline,                                         # I: Deadline
            lat,                                              # J: Latitude
            lng,                                              # K: Longitude
            row.get("geopin", ""),                            # L
            _parse_socrata_date_str(row.get("initinspection")),  # M
            row.get("permit_type", ""),                       # N
            row.get("permit_status", ""),                     # O
            _parse_socrata_date_str(row.get("permit_filing")),# P
            _parse_socrata_date_str(row.get("next_hearing")), # Q
            row.get("stage", ""),                             # R
            row.get("o_c", ""),                               # S
            row.get("zipcode", ""),                           # T
        ])
        seen_cases.add(caseno)

    print(f"Processed {len(processed_data)-1} valid records.")

    # Google Sheets Authentication
    creds_json = os.environ.get("GOOGLE_CREDENTIALS")
    if not creds_json:
        print("Error: GOOGLE_CREDENTIALS environment variable not set.")
        print("Skipping database update due to missing credentials.")
        return

    try:
        print("Authenticating with Google Sheets...")
        creds_dict = json.loads(creds_json)
        scopes = ['https://www.googleapis.com/auth/spreadsheets']
        credentials = Credentials.from_service_account_info(creds_dict, scopes=scopes)
        client = gspread.authorize(credentials)

        print(f"Opening spreadsheet {SPREADSHEET_ID}...")
        sheet = client.open_by_key(SPREADSHEET_ID).sheet1

        print("Clearing existing data in columns A:T (preserves enrichment cols U+)...")
        sheet.batch_clear(["A:T"])

        print("Writing new data...")
        sheet.update(processed_data, "A1")

        print("Database update successful!")

    except gspread.exceptions.SpreadsheetNotFound:
        print("\n[CRITICAL ERROR] Spreadsheet Not Found!")
        print("This usually means your Google Service Account does not have permission to view the spreadsheet.")
        print(f"ACTION REQUIRED: Open your Google Sheet and click 'Share'. Add the following email address as an Editor:")
        print(f"==> {creds_dict.get('client_email', 'UNKNOWN_EMAIL')} <==\n")
        sys.exit(1)
    except PermissionError as e:
        print("\n[CRITICAL ERROR] Google API Permission Error!")
        print(f"Error Details: {e}")
        if e.__cause__:
            print(f"Underlying Cause: {e.__cause__}")
        print("This usually means the Google Sheets API is disabled in your Google Cloud Console.")
        print("ACTION REQUIRED: Follow the link provided in the error message above to enable the Google Sheets API for your project.")
        print("Wait a few minutes after enabling it, then try running the scraper again.\n")
        sys.exit(1)
    except gspread.exceptions.APIError as e:
        print("\n[CRITICAL ERROR] Google API Error!")
        print(f"Error Details: {e}")
        print("If this is a permission error (403), please ensure you have shared the Google Sheet with the Service Account email address:")
        print(f"==> {creds_dict.get('client_email', 'UNKNOWN_EMAIL')} <==\n")
        sys.exit(1)
    except Exception as e:
        print("Failed to update Google Sheet:")
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()