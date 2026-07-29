function Write-IrisPresetCatalog
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [object] $Context
    )

    $camera = $Context.expected.camera
    $catalog = [ordered] @{
        schema_version = 1
        time_presets = @([ordered] @{
            id = $Context.time_preset_id
            tick = [long] $Context.expected.day_time
            weather = $Context.weather_preset_id
        })
        settings_presets = @([ordered] @{ id = $Context.settings_preset_id })
        worlds = @([ordered] @{
            id = $Context.save_id
            save_name = $Context.save_id
            dimensions = @($Context.dimension_id)
            cameras = @([ordered] @{
                id = $Context.camera_preset_id
                dimension_id = $Context.dimension_id
                position = @([double] $camera.x, [double] $camera.y, [double] $camera.z)
                yaw = [double] $camera.yaw
                pitch = [double] $camera.pitch
                default_fov = [double] $Context.fov
            })
        })
    }
    $configRoot = Join-Path $Scope.GameDir "config\vibris"
    [void] (New-Item -ItemType Directory -Path $configRoot)
    $path = Join-Path $configRoot "presets.json"
    [System.IO.File]::WriteAllText($path, ($catalog | ConvertTo-Json -Depth 10))
    return $path
}