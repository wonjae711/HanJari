$ErrorActionPreference = 'Stop'
# Use the installed Windows PowerShell app identity for native toast history.
# No app identity, registry setting, or notification preference is changed.
$app = Get-StartApps | Where-Object { $_.Name -eq 'Windows PowerShell' } | Select-Object -First 1
if ($null -eq $app) { throw 'Windows PowerShell Start app identity was not found.' }
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.UI.Notifications.ToastNotifier, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.UI.Notifications.NotificationSetting, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.UI.Notifications.ToastNotificationHistory, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
$xml = New-Object Windows.Data.Xml.Dom.XmlDocument
$xml.LoadXml('<toast><visual><binding template="ToastGeneric"><text></text><text></text></binding></visual></toast>')
$nodes = $xml.GetElementsByTagName('text')
$nodes.Item(0).AppendChild($xml.CreateTextNode($env:HANJARI_NOTICE_TITLE)) | Out-Null
$nodes.Item(1).AppendChild($xml.CreateTextNode($env:HANJARI_NOTICE_TEXT)) | Out-Null
$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
$toast.Tag = 'hanjari'
$toast.Group = 'automation'
$toast.ExpirationTime = [DateTimeOffset]::Now.AddHours(1)
$notifier = [Windows.UI.Notifications.ToastNotifier][Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app.AppID)
if ($null -ne $notifier.Setting -and $notifier.Setting.ToString() -ne 'Enabled') {
    throw ('Windows notification setting: ' + $notifier.Setting.ToString())
}
$notifier.Show($toast)
Start-Sleep -Milliseconds 800
$recorded = [Windows.UI.Notifications.ToastNotificationManager]::History.GetHistory($app.AppID) |
    Where-Object { $_.Tag -eq 'hanjari' -and $_.Group -eq 'automation' }
if ($null -eq $recorded) { throw 'Toast was not found in Windows notification history.' }
Write-Output 'Windows toast history confirmed. Banner visibility still requires user confirmation.'
