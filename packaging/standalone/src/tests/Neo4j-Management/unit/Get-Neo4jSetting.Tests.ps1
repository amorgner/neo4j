$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$sut = (Split-Path -Leaf $MyInvocation.MyCommand.Path).Replace(".Tests.", ".")
$common = Join-Path (Split-Path -Parent $here) 'Common.ps1'
. $common

Import-Module "$src\Neo4j-Management.psm1"

InModuleScope Neo4j-Management {
  Describe "Get-Neo4jSetting" {
  
    Context "Invalid or missing specified neo4j installation" {
      $serverObject = (New-Object -TypeName PSCustomObject -Property @{
        'Home' =  'TestDrive:\some-dir-that-doesnt-exist';
        'ServerVersion' = '3.0';
        'ServerType' = 'Enterprise';
        'DatabaseMode' = '';
      })
      $result = Get-Neo4jSetting -Neo4jServer $serverObject
  
      It "return null if invalid directory" {
        $result | Should BeNullOrEmpty      
      }
    }
  
    Context "Invalid or missing server object" {
      It "throws error for an invalid server object" {
        { Get-Neo4jSetting -Neo4jServer (New-Object -TypeName PSCustomObject) -ErrorAction Stop } | Should Throw
      }
    }
    
    Context "Missing configuration file" {
      $serverObject = (New-Object -TypeName PSCustomObject -Property @{
        'Home' =  'TestDrive:\some-dir-that-doesnt-exist';
        'ServerVersion' = '3.0';
        'ServerType' = 'Enterprise';
        'DatabaseMode' = '';
      })
      Mock Test-Path { return $false }  
      Mock Test-Path { return $true } -ParameterFilter { ([string]$Path).EndsWith('neo4j.conf') }
      Mock Test-Path { return $false } -ParameterFilter { ([string]$Path).EndsWith('neo4j-wrapper.conf') }
      Mock Get-KeyValuePairsFromConfFile { return @{ "setting"="value"; } } -ParameterFilter { $Filename.EndsWith('neo4j.conf') }
      Mock Get-KeyValuePairsFromConfFile { throw 'missing file' }           -ParameterFilter { $Filename.EndsWith('neo4j-wrapper.conf') }
      
      $result = Get-Neo4jSetting -Neo4jServer $serverObject
      
      It "ignore the missing file" {
        $result.Name | Should Be "setting"
        $result.Value | Should Be "value"
      } 
    }
  
    Context "Simple configuration settings" {
      Mock Test-Path { return $true }
      Mock Get-KeyValuePairsFromConfFile { return @{ "setting1"="value1"; } } -ParameterFilter { $Filename.EndsWith('neo4j.conf') }
      Mock Get-KeyValuePairsFromConfFile { return @{ "setting2"="value2"; } } -ParameterFilter { $Filename.EndsWith('neo4j-wrapper.conf') }

      $serverObject = (New-Object -TypeName PSCustomObject -Property @{
        'Home' =  'TestDrive:\some-dir-that-doesnt-exist';
        'ServerVersion' = '3.0';
        'ServerType' = 'Enterprise';
        'DatabaseMode' = '';
      })
      
      $result = Get-Neo4jSetting -Neo4jServer $serverObject
      
      It "one setting per file" {
        $result.Count | Should Be 2
      } 
  
      # Parse the results and make sure the expected results are there
      $unknownSetting = $false
      $neo4jProperties = $false
      $neo4jServerProperties = $false
      $neo4jWrapper = $false
      $result | ForEach-Object -Process {
        $setting = $_
        switch ($setting.Name) {
          'setting1' { $neo4jServerProperties = ($setting.ConfigurationFile -eq 'neo4j.conf') -and ($setting.IsDefault -eq $false) -and ($setting.Value -eq 'value1') }
          'setting2' { $neo4jWrapper =          ($setting.ConfigurationFile -eq 'neo4j-wrapper.conf') -and ($setting.IsDefault -eq $false) -and ($setting.Value -eq 'value2') }
          default { $unknownSetting = $true}
        }
      }
  
      It "returns settings for file neo4j.conf" {
        $neo4jServerProperties | Should Be $true
      } 
      It "returns settings for file neo4j-wrapper.conf" {
        $neo4jWrapper | Should Be $true
      } 
  
      It "returns no unknown settings" {
        $unknownSetting | Should Be $false
      } 
    }
  
    Context "Configuration settings with multiple values" {
      Mock Test-Path { return $true }
      Mock Get-KeyValuePairsFromConfFile { return @{ "setting1"="value1"; "setting2"=@("value2","value3","value4"; ); } } -ParameterFilter { $Filename.EndsWith('neo4j.conf') }
      Mock Get-KeyValuePairsFromConfFile { return @{} } -ParameterFilter { $Filename.EndsWith('neo4j-wrapper.conf') }

      $serverObject = (New-Object -TypeName PSCustomObject -Property @{
        'Home' =  'TestDrive:\some-dir-that-doesnt-exist';
        'ServerVersion' = '3.0';
        'ServerType' = 'Enterprise';
        'DatabaseMode' = '';
      })      
      $result = Get-Neo4jSetting -Neo4jServer $serverObject
      
      # Parse the results and make sure the expected results are there
      $singleSetting = $null
      $multiSetting = $null
      $result | ForEach-Object -Process {
        $setting = $_
        switch ($setting.Name) {
          'setting1' { $singleSetting = $setting }
          'setting2' { $multiSetting = $setting }
        }
      }
      
      It "returns single settings" {
        ($singleSetting -ne $null) | Should Be $true
      }
      It "returns a string for single settings" {
        $singleSetting.Value.GetType().ToString() | Should Be "System.String"
      }

      It "returns multiple settings" {
        ($multiSetting -ne $null) | Should Be $true
      }
      It "returns an object array for multiple settings" {
        $multiSetting.Value.GetType().ToString() | Should Be "System.Object[]"
      }
      It "returns an object array for multiple settings with the correct size" {
        $multiSetting.Value.Count | Should Be 3
      }
    }  
  }
}
