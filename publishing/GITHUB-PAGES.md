# Publish the Face Gallery privacy page

GitHub refused this session's upload with HTTP 403, Resource not accessible by integration. No files were committed and the page is not yet live.

1. Upload the `docs` folder from this source package to the `main` branch of `ashwinidhatterwal/faceGallery`. It contains `index.html`, `privacy.html` and `.nojekyll`. Uploading the whole source package's contents also includes these files.
2. Open https://github.com/ashwinidhatterwal/faceGallery/settings/pages .
3. Under Build and deployment, choose **Deploy from a branch**; choose **main** and **/docs**, then **Save**.
4. Wait for the Pages deployment to succeed, then open https://ashwinidhatterwal.github.io/faceGallery/privacy.html while signed out. Verify it shows Face Gallery and ashwinidhatterwal@gmail.com.
5. Enter that verified URL in Play Console's Privacy policy field.

The proposed URL is already configured in store-listing.json, but its inclusion does not establish that the site is published. GitHub Pages configuration does not require an app signing key or a paid custom domain. Public policy files include only the app policy and the support details you provided.
