import requests
import gspread
from google.oauth2.service_account import Credentials
import os
import json
from datetime import datetime, timedelta

# Constants
SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE'
SOCRATA_ENDPOINT = 'https://data.nola.gov/resource/gjzc-adg8.json'
# Filtering by bounds rather than neighborhood since 'neighborhood' column doesn't exist
# We will use the same bounding box filtering as the frontend does:
# Bounds approx: North: 29.98, West: -90.03, South: 29.95, East: -89.98 for Lower Ninth
BOUNDS = "within_box(the_geom, 29.98, -90.03, 29.95, -89.98)"
QUERY = f"?$select=geoaddress AS address, prevhearingresult AS status, casefiled AS notice_date, the_geom AS location, caseno AS casenumber&$where={BOUNDS} AND prevhearingresult IN('Guilty', 'Uncommitted')&$limit=50000"

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
    # Add Headers
    processed_data.append(["casenumber", "address", "status", "notice_date", "lat", "lng", "deadline"])

    seen_cases = set()

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

        processed_data.append([caseno, address, status, notice_date, lat, lng, deadline])
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

        print("Clearing existing data...")
        sheet.clear()

        print("Writing new data...")
        sheet.update(processed_data)

        print("Database update successful!")

    except Exception as e:
        print(f"Failed to update Google Sheet: {e}")

if __name__ == "__main__":
    main()