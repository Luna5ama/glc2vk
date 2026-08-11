function Write-IrisPresetCatalog
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [object] $Context
    )

    $camera = $Context.expected.camera
    $catalog = [ordered] @{
        schema_version = 2
        presets = @([ordered] @{
            id = "automation"
            save_id = $Context.save_id
            save_name = $Context.save_id
            dimension_id = $Context.dimension_id
            position = @([double] $camera.x, [double] $camera.y, [double] $camera.z)
            yaw = [double] $camera.yaw
            pitch = [double] $camera.pitch
            fov = [double] $Context.fov
            tick = [long] $Context.expected.day_time
            weather = $Context.weather_preset_id
            resolution = @([int] $Context.resolution.width, [int] $Context.resolution.height)
            settings_preset_id = $Context.settings_preset_id
            tags = @("automation")
        })
    }
    $configRoot = Join-Path $Scope.GameDir "config\vibris"
    [void] (New-Item -ItemType Directory -Path $configRoot)
    $path = Join-Path $configRoot "presets.json"
    [System.IO.File]::WriteAllText($path, ($catalog | ConvertTo-Json -Depth 10))
    return $path
}
