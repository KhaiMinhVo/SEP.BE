# Collaboration draft videos

## R2 setup

Create a private R2 bucket and a read/write bucket-scoped S3 API token. Keep the public development URL disabled. Configure the API environment using the placeholders in `.env.example`. Set `COLLABORATION_VIDEO_R2_ENDPOINT` to the S3 API endpoint for the account, without the bucket path.

Allow the actual browser origins in the bucket CORS settings. This example is for the production bucket. Allow localhost separately in QA.

```json
[
  {
    "AllowedOrigins": ["https://www.influencermatch.site"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["Content-Type", "Content-Length", "Cache-Control", "If-None-Match", "Range"],
    "ExposeHeaders": ["ETag", "Accept-Ranges", "Content-Length", "Content-Range"],
    "MaxAgeSeconds": 3600
  }
]
```

## Release and smoke test

Deploy the API with the bucket settings and `COLLABORATION_VIDEO_REQUIRED=false`, then deploy the frontend. Enable `COLLABORATION_VIDEO_REQUIRED=true` in QA, restart the API and check a real browser upload and playback against R2. Use an MP4 with H.264/AAC and verify playback and seeking through the collaboration and campaign history views.

Enable video-only submissions in production after QA and repeat with Brand and Creator test accounts. Check that an account outside the collaboration cannot obtain a playback URL. Legacy submissions keep their existing links. If submission needs to fall back to links, disable the flag and keep the bucket configuration in place so committed videos still play.

The API removes abandoned upload objects after 24 hours. Keep committed objects in the bucket so historical revisions remain available.
