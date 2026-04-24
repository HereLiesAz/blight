// To use this proxy, go to script.google.com and create a new project.
// Paste this code, save it, and then go to Deploy > New Deployment.
// Select "Web app" as the type.
// Execute as: "Me"
// Who has access: "Anyone"
// Copy the Web App URL and paste it into index.html as PROXY_URL.

const SPREADSHEET_ID = '1O5zIhogpzmZLRn36X1Rt6cZUkWeYb2dzUgBTQszq_oE';

function doPost(e) {
  try {
    // To trigger the scrape securely, you MUST add a GITHUB_PAT property
    // to this Google Apps Script project.
    // 1. Go to Project Settings (Gear icon) -> Script Properties
    // 2. Add Property: GITHUB_PAT -> [Your Token]

    const githubPat = PropertiesService.getScriptProperties().getProperty('GITHUB_PAT');
    if (!githubPat) {
      return ContentService.createTextOutput(JSON.stringify({
        status: "error",
        error: "Missing GITHUB_PAT in Script Properties."
      })).setMimeType(ContentService.MimeType.JSON);
    }

    const url = 'https://api.github.com/repos/nhenia/blight/dispatches';
    const payload = { event_type: 'update-database' };

    const options = {
      method: 'post',
      contentType: 'application/json',
      headers: {
        'Accept': 'application/vnd.github.v3+json',
        'Authorization': 'token ' + githubPat
      },
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    };

    const response = UrlFetchApp.fetch(url, options);

    if (response.getResponseCode() >= 200 && response.getResponseCode() < 300) {
       return ContentService.createTextOutput(JSON.stringify({ status: "success" }))
        .setMimeType(ContentService.MimeType.JSON);
    } else {
       return ContentService.createTextOutput(JSON.stringify({
         status: "error",
         error: response.getContentText()
       })).setMimeType(ContentService.MimeType.JSON);
    }

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
