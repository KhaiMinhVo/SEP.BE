# Campaign cover images

`POST /api/v1/brands/{brandId}/campaign-images` accepts multipart field `file`.
Requires CREATE_CAMPAIGN or UPDATE_CAMPAIGN and access to the brand profile.
Returns the standard API envelope with `data.imageUrl`; submit that URL in the
existing campaign create/update request. Uploading alone does not modify a campaign.

Supported files: JPG/PNG, maximum 5 MiB and 20 megapixels. The server inspects and
decodes image bytes instead of trusting file extensions or browser MIME types.

Configure these environment variables on the backend:

```text
CAMPAIGN_IMAGE_R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
CAMPAIGN_IMAGE_R2_BUCKET=<public-campaign-images-bucket>
CAMPAIGN_IMAGE_R2_ACCESS_KEY_ID=<server-only-access-key>
CAMPAIGN_IMAGE_R2_SECRET_ACCESS_KEY=<server-only-secret>
CAMPAIGN_IMAGE_PUBLIC_BASE_URL=https://<public-image-domain>
```

Endpoint and credentials may fall back to existing COLLABORATION_VIDEO_R2_*
settings, but the bucket and public base URL must be configured explicitly.
Use a separate public image bucket; do not expose the private collaboration video
bucket. The credentials must have write access to the image bucket. Configure
public reads via an R2 custom domain or a suitable public bucket URL. Browser CORS
for direct uploads is unnecessary: file bytes are uploaded through the backend.
The reverse proxy must allow multipart requests up to 6 MiB. No credentials belong
in frontend environment variables.

Missing storage configuration or failed writes return CAMPAIGN_IMAGE_UNAVAILABLE
(503). HTTPS links remain available independently. The returned image URL is stable,
not an expiring signed upload URL. Verify upload, public GET and campaign reload on
the deployed environment; local unit tests do not verify R2 connectivity.

Removing/replacing an image clears/changes the campaign URL; it does not delete an
R2 object. Abandoned uploads can also remain. Garbage collection is not included;
do not apply a blanket expiry policy that would delete images still in use.
