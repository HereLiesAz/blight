// To use this proxy, go to script.google.com and create a new project.
// Paste this code, save it, and then go to Deploy > New Deployment.
// Select "Web app" as the type.
// Execute as: "Me"
// Who has access: "Anyone"
// Copy the Web App URL and paste it into index.html as PROXY_URL.

const SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE';

function parseSocrataDateGAS(dateStr) {
  if (!dateStr) return null;
  try {
    if (dateStr.includes('T')) {
      const parts = dateStr.split('T')[0].split('-');
      return new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]));
    } else if (dateStr.includes('-')) {
      const parts = dateStr.split('-');
      return new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]));
    } else if (dateStr.length >= 8) {
      return new Date(parseInt(dateStr.substring(0, 4)), parseInt(dateStr.substring(4, 6)) - 1, parseInt(dateStr.substring(6, 8)));
    }
  } catch (e) {}
  return null;
}

function doPost(e) {
  try {
    const SOCRATA_ENDPOINT = 'https://data.nola.gov/resource/gjzc-adg8.json';
    const BOUNDS = "within_box(the_geom, 29.98, -90.03, 29.95, -89.98)";
    const QUERY = "?$select=geoaddress AS address, prevhearingresult AS status, casefiled AS notice_date, the_geom AS location, caseno AS casenumber&$where=" + BOUNDS + " AND prevhearingresult IN('Guilty', 'Uncommitted')&$limit=50000";

    const response = UrlFetchApp.fetch(SOCRATA_ENDPOINT + QUERY);
    if (response.getResponseCode() !== 200) {
      throw new Error("Failed to fetch from Socrata: " + response.getContentText());
    }
    const data = JSON.parse(response.getContentText());

    const processedData = [];
    processedData.push([
        "Address",
        "Neighborhood",
        "Name/Type",
        "Features & 2026 Status",
        "Previous Statuses",
        "Updated on",
        "Case Number",
        "Notice Date",
        "Deadline",
        "Latitude",
        "Longitude"
    ]);

    const seenCases = {};
    // Note: Utilities.formatDate is easier for formatting in Google Apps Script
    // but building the date manually works globally without timezone dependencies
    const now = new Date();
    const currentDateStr = (now.getUTCMonth()+1).toString().padStart(2, '0') + '/' +
                           now.getUTCDate().toString().padStart(2, '0') + '/' +
                           now.getUTCFullYear() + ' ' +
                           now.getUTCHours().toString().padStart(2, '0') + ':' +
                           now.getUTCMinutes().toString().padStart(2, '0') + ':' +
                           now.getUTCSeconds().toString().padStart(2, '0') + ' UTC';

    for (let i = 0; i < data.length; i++) {
        const row = data[i];
        const caseno = row.casenumber;
        if (!caseno || seenCases[caseno]) continue;

        if (!row.location || !row.location.coordinates) continue; // Skip missing location

        const lng = row.location.coordinates[0];
        const lat = row.location.coordinates[1];

        const address = row.address || "Unknown";
        let status = (row.status || "").toLowerCase().includes('guilty') ? 'Guilty' : 'Uncommitted';

        let noticeDateStr = "";
        let deadlineStr = "";
        const noticeDateObj = parseSocrataDateGAS(row.notice_date);
        if (noticeDateObj) {
            noticeDateStr = (noticeDateObj.getMonth() + 1).toString().padStart(2, '0') + '/' +
                            noticeDateObj.getDate().toString().padStart(2, '0') + '/' +
                            noticeDateObj.getFullYear();

            const deadlineObj = new Date(noticeDateObj.getTime() + 30 * 24 * 60 * 60 * 1000);
            deadlineStr = (deadlineObj.getMonth() + 1).toString().padStart(2, '0') + '/' +
                          deadlineObj.getDate().toString().padStart(2, '0') + '/' +
                          deadlineObj.getFullYear();
        }

        processedData.push([
            address,                 // A: Address
            "Lower Ninth Ward",      // B: Neighborhood
            "",                      // C: Name/Type (blank for now)
            status,                  // D: Features & 2026 Status (Status)
            "",                      // E: Previous Statuses (blank for now)
            currentDateStr,          // F: Updated on
            caseno,                  // G: Case Number
            noticeDateStr,           // H: Notice Date
            deadlineStr,             // I: Deadline
            lat,                     // J: Latitude
            lng                      // K: Longitude
        ]);
        seenCases[caseno] = true;
    }

    const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheets()[0];
    sheet.getRange('A:K').clearContent();
    sheet.getRange(1, 1, processedData.length, 11).setValues(processedData);

    return ContentService.createTextOutput(JSON.stringify({ status: "success", rows: processedData.length - 1 }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({ status: "error", error: error.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

function doGet(e) {
  try {
    const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheets()[0];
    const data = sheet.getDataRange().getValues();

    if (data.length === 0) {
      return ContentService.createTextOutput(JSON.stringify([]))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // Process headers
    const headers = data[0].map(h => h.toString().toLowerCase().trim());

    // Find column indices dynamically based on the requested schema
    let colIndices = { casenumber: -1, address: -1, status: -1, notice_date: -1, lat: -1, lng: -1, deadline: -1 };

    headers.forEach((label, index) => {
        if (label.includes('case number') || label.includes('casenumber')) colIndices.casenumber = index;
        else if (label.includes('address')) colIndices.address = index;
        else if (label.includes('features') || label.includes('status')) colIndices.status = index;
        else if (label.includes('notice date')) colIndices.notice_date = index;
        else if (label.includes('deadline')) colIndices.deadline = index;
        else if (label.includes('latitude') || label === 'lat') colIndices.lat = index;
        else if (label.includes('longitude') || label === 'lng') colIndices.lng = index;
    });

    // Fallback indices if headers are missing (matching the 11-column setup)
    if (colIndices.address === -1) colIndices.address = 0;
    if (colIndices.status === -1) colIndices.status = 3;
    if (colIndices.casenumber === -1) colIndices.casenumber = 6;
    if (colIndices.notice_date === -1) colIndices.notice_date = 7;
    if (colIndices.deadline === -1) colIndices.deadline = 8;
    if (colIndices.lat === -1) colIndices.lat = 9;
    if (colIndices.lng === -1) colIndices.lng = 10;

    const result = [];

    // Skip header row
    for (let i = 1; i < data.length; i++) {
      const row = data[i];
      if (!row[colIndices.casenumber] && !row[colIndices.lat]) continue; // Skip empty rows

      result.push({
        casenumber: row[colIndices.casenumber],
        address: row[colIndices.address],
        status: row[colIndices.status],
        notice_date: row[colIndices.notice_date],
        deadline: row[colIndices.deadline],
        lat: row[colIndices.lat],
        lng: row[colIndices.lng]
      });
    }

    return ContentService.createTextOutput(JSON.stringify(result))
      .setMimeType(ContentService.MimeType.JSON);

  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({ error: error.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}
