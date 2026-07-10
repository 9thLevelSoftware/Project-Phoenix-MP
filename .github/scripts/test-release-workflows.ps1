$ErrorActionPreference = 'Stop'

function Assert-Contains([string] $Path, [string] $Pattern, [string] $Message) {
  if ((Get-Content -Raw $Path) -notmatch $Pattern) { throw "${Path}: $Message" }
}

Assert-Contains '.github/workflows/android-release-apk.yml' '(?ms)workflow_call:.*source_ref:.*required: true.*release_tag:.*required: true' 'APK reusable inputs missing.'
Assert-Contains '.github/workflows/android-release-apk.yml' 'ref: .*inputs\.source_ref' 'APK checkout not explicit.'
Assert-Contains '.github/workflows/android-release-apk.yml' 'gh release view "\$\{\{ inputs\.release_tag \}\}"' 'APK target release not verified.'
Assert-Contains '.github/workflows/ios-release-ipa.yml' '(?ms)workflow_call:.*source_ref:.*required: true.*release_tag:.*required: true' 'IPA reusable inputs missing.'
Assert-Contains '.github/workflows/ios-release-ipa.yml' 'ref: .*inputs\.source_ref' 'IPA checkout not explicit.'
Assert-Contains '.github/workflows/ios-release-ipa.yml' 'gh release view "\$\{\{ inputs\.release_tag \}\}"' 'IPA target release not verified.'
Assert-Contains '.github/workflows/android-playstore.yml' '(?ms)workflow_call:.*source_ref:.*required: true' 'Play Store reusable source input missing.'
Assert-Contains '.github/workflows/ios-testflight.yml' '(?ms)workflow_call:.*source_ref:.*required: true' 'TestFlight reusable source input missing.'
Assert-Contains '.github/workflows/release-all.yml' 'MARKETING_VERSION' 'New-release workflow does not validate iOS version.'
Assert-Contains '.github/workflows/release-all.yml' 'uses: \./\.github/workflows/android-release-apk\.yml' 'New-release workflow does not call the APK workflow.'
Assert-Contains '.github/workflows/release-all.yml' 'needs: \[create-release, android-apk, ios-ipa\]' 'Store publication is not gated on both assets.'
Assert-Contains '.github/workflows/release-all-existing.yml' 'release_tag:' 'Existing-release workflow lacks tag input.'
Assert-Contains '.github/workflows/release-all-existing.yml' 'gh release delete-asset' 'Existing-release workflow does not delete assets.'
Assert-Contains '.github/workflows/release-all-existing.yml' 'source_ref: main' 'Existing-release workflow does not use main.'
Write-Output 'Release workflow contracts passed.'
