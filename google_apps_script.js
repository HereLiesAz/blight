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
    // Basic scrape logic directly in GAS
    const SOCRATA_ENDPOINT = 'https://data.nola.gov/resource/gjzc-adg8.json';
    const BOUNDS = "within_box(the_geom, 29.98, -90.03, 29.95, -89.98)";
    const QUERY = "?$select=geoaddress AS address, prevhearingresult AS status, casefiled AS notice_date, the_geom AS location, caseno AS casenumber&$where=" + BOUNDS + " AND prevhearingresult IN('Guilty', 'Uncommitted')&$limit=50000";

    const response = UrlFetchApp.fetch(SOCRATA_ENDPOINT + QUERY);
    const data = JSON.parse(response.getContentText());

    const processedData = [];
    processedData.push(["casenumber", "address", "status", "notice_date", "lat", "lng", "deadline"]);

    const seenCases = {};
    for (let i = 0; i < data.length; i++) {
        const row = data[i];
        const caseno = row.casenumber;
        if (!caseno || seenCases[caseno]) continue;

        if (!row.location || !row.location.coordinates) continue;

        const lng = row.location.coordinates[0];
        const lat = row.location.coordinates[1];

        const address = row.address || "Unknown";
        let status = (row.status || "").toLowerCase().includes('guilty') ? 'Guilty' : 'Uncommitted';

        let noticeDateStr = "";
        let deadlineStr = "";
        const noticeDateObj = parseSocrataDateGAS(row.notice_date);
        if (noticeDateObj) {
            noticeDateStr = (noticeDateObj.getMonth() + 1) + '/' + noticeDateObj.getDate() + '/' + noticeDateObj.getFullYear();
            const deadlineObj = new Date(noticeDateObj.getTime() + 30 * 24 * 60 * 60 * 1000);
            deadlineStr = (deadlineObj.getMonth() + 1) + '/' + deadlineObj.getDate() + '/' + deadlineObj.getFullYear();
        }

        processedData.push([caseno, address, status, noticeDateStr, lat, lng, deadlineStr]);
        seenCases[caseno] = true;
    }

    const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheets()[0];
    sheet.clear();
    sheet.getRange(1, 1, processedData.length, processedData[0].length).setValues(processedData);

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

    // Find column indices dynamically
    let colIndices = { casenumber: -1, address: -1, status: -1, notice_date: -1, lat: -1, lng: -1 };

    headers.forEach((label, index) => {
        if (label.includes('casenumber') || label.includes('case number') || label === 'caseno') colIndices.casenumber = index;
        else if (label.includes('address')) colIndices.address = index;
        else if (label.includes('status') || label.includes('result')) colIndices.status = index;
        else if (label.includes('notice') || label.includes('date')) colIndices.notice_date = index;
        else if (label === 'lat' || label === 'latitude') colIndices.lat = index;
        else if (label === 'lng' || label === 'longitude') colIndices.lng = index;
    });

    // Fallback indices if headers are missing
    if (colIndices.casenumber === -1) colIndices.casenumber = 0;
    if (colIndices.address === -1) colIndices.address = 1;
    if (colIndices.status === -1) colIndices.status = 2;
    if (colIndices.notice_date === -1) colIndices.notice_date = 3;
    if (colIndices.lat === -1) colIndices.lat = 4;
    if (colIndices.lng === -1) colIndices.lng = 5;

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
