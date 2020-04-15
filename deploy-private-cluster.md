0. 準備作業
1. 共通準備
2. オンプレ模倣環境の作成
3. AKS 配置環境の準備
4. AKS 管理マシンの準備
5. AKS クラスタの作成
6. アプリケーションの配置
7. オンプレ模倣環境から AKS アプリへの接続
8. インターネットからの接続
9. ノード自動再起動

# 作業の進め方について

- 特に指定がない限り、Cloud Shell (Bash) からの作業を想定しています。Ownerロール権限と、Azure ADに対してService Principalを作成する権限を持つユーザで作業を行ってください。（これらが足りない場合には#5のAKSクラスタ作成に関する手順が変わります。詳細はdocs参照。）
- Cloud Shellは20分でタイムアウトしてしまうため、そこまでに設定した環境変数が失われてしまいます。これを避けるため、各作業セクションにある青文字のパラメータ設定部分を取り出して、何かしらのテキストファイルなどにコピーして残しておくことを推奨します。青文字の部分だけを再実行して環境変数を再設定すれば、以降の作業が再開できるようにスクリプトが作られています。
- 赤文字で書かれている部分については、各自の環境に併せて設定値を変更してください。
- 緑文字で書かれている部分は、プライベートAKSクラスタにするか否かの変更点です。（プライベートAKSクラスタ＝k8s Master APIをPrivate EndpointによりVNET内に引き込んだ形態。）

# 0. 準備作業
サブスクリプションの選択
- az コマンド拡張のインストール
- AKS プレビュー機能のインストール
 
## Select Subscription

```bash
SUBSCRIPTION_NAME=nakama-subscription03
az account set -s $SUBSCRIPTION_NAME
SUBSCRIPTION_ID=$(az account show -s $SUBSCRIPTION_NAME --query id -o tsv)
```

## Parameters ★ NAME_PREFIXは各自で書き換えてください（名前重複防止）

```bash
NAME_PREFIX=azrefarc7
LOCATION=japaneast
```
 
## Install az extensions

```bash
# az extension list-available --output table
az extension add --name application-insights
az extension add --name aks-preview
az extension add --name azure-firewall
```
 
## Install AKS Private Link Preview

```bash
# ※ 時間かかるので注意（20 分ぐらい）
az feature register --name AKSPrivateLinkPreview --namespace Microsoft.ContainerService
# 以下が Registering -> Registered になるまで繰り返す
az feature list -o table --query "[?contains(name, 'Microsoft.ContainerService/AKSPrivateLinkPreview')].{Name:name,State:properties.state}"
 
az provider register --namespace Microsoft.ContainerService
az provider register --namespace Microsoft.Network
```

# 1. 共通準備

- リソースグループの作成
- 診断ログストレージの作成
- サブスクリプションのアクティビティログの有効化
- Log Analytics の作成
- Application Insights の作成

```bash
# Parameters
RG_OPS="${NAME_PREFIX}-ops-rg"
RG_AKS="${NAME_PREFIX}-aks-rg"
RG_DMZ="${NAME_PREFIX}-dmz-rg"
RG_ONP="${NAME_PREFIX}-onprem-rg"
 
DIAG_STORAGE_NAME="${NAME_PREFIX}aksdiag"
DIAG_LA_WS_NAME="${NAME_PREFIX}-aks-laws"
DIAG_AI_APP_NAME="${NAME_PREFIX}-aks-ai"
 
# Create Resouce Group
az group create --name $RG_OPS --location $LOCATION
az group create --name $RG_AKS --location $LOCATION
az group create --name $RG_DMZ --location $LOCATION
az group create --name $RG_ONP --location $LOCATION
 
# Create Diagnostics Storage
az storage account create --name $DIAG_STORAGE_NAME --resource-group $RG_OPS --location $LOCATION --sku Standard_GRS --encryption-services blob --kind StorageV2 --default-action Deny
 
# Create Log Analytics & AppInsights
az monitor log-analytics workspace create --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --location $LOCATION --retention-time 90
az monitor app-insights component create --app $DIAG_AI_APP_NAME --location $LOCATION --resource-group $RG_OPS --application-type web
 
# Enable Subscription Activity Logging
# https://docs.microsoft.com/en-us/azure/azure-monitor/platform/diagnostic-settings
# ※ 現状では az CLI からでは診断設定が作れない (ARM での展開が必要)
# https://docs.microsoft.com/en-us/azure/azure-monitor/platform/diagnostic-settings-template
# 現時点では UI から実施
# Dashboard -> Subscriptions -> <subscription> -> Activity Log -> Diagnostics Settings
# Add Diagnostics Settings
# Diagnostics settings name : ActivityLog
# Category details : All (Administrative, Security, ServiceHealth, Alert, Recommendation, Policy, Autoscale, ResourceHealth)
# Destination : Send to Log Analytics, Archive to a storage account
DIAG_STORAGE_ID=$(az storage account show --name $DIAG_STORAGE_NAME --query id -o tsv)
DIAG_LA_WS_GUID=$(az monitor log-analytics workspace show --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --query customerId -o tsv)
DIAG_LA_WS_ID=$(az monitor log-analytics workspace show --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --query id -o tsv)
DIAG_LA_WS_KEY=$(az monitor log-analytics workspace get-shared-keys --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --query primarySharedKey -o tsv)
```

# 2. オンプレ模倣環境の作成

- VNET・サブネットの作成
- uservm1 の作成

```bash
# Parameters
ONP_VNET_NAME="${NAME_PREFIX}-onprem-vnet"
ONP_VNET_ADDRESS_PREFIXES=10.0.0.0/16
ONP_VNET_SUBNET_DEF_NAME=Default
ONP_VNET_SUBNET_DEF_ADDRESS_PREFIX=10.0.0.0/24
ONP_VNET_SUBNET_IES_NAME=InternalEndpointSubnet
ONP_VNET_SUBNET_IES_ADDRESS_PREFIX=10.0.250.0/24
 
# Create Onpremise VNET
az network vnet create --resource-group $RG_ONP --name $ONP_VNET_NAME --address-prefixes $ONP_VNET_ADDRESS_PREFIXES
az network vnet subnet create --name $ONP_VNET_SUBNET_DEF_NAME --address-prefix $ONP_VNET_SUBNET_DEF_ADDRESS_PREFIX --resource-group $RG_ONP --vnet-name $ONP_VNET_NAME
az network vnet subnet create --name $ONP_VNET_SUBNET_IES_NAME --address-prefix $ONP_VNET_SUBNET_IES_ADDRESS_PREFIX --resource-group $RG_ONP --vnet-name $ONP_VNET_NAME
 
# Create uservm1 (Public IP + Open RDP Port)
# Publisher:Offer:Sku:Version
# az vm image list --publisher MicrosoftWindowsDesktop --offer Windows-10 --sku 19h2 --all -o tsv
az network public-ip create --name uservm1-ip --resource-group $RG_ONP --sku Standard
ONP_VNET_SUBNET_DEF_ID=$(az network vnet subnet show --resource-group $RG_ONP --vnet-name $ONP_VNET_NAME --name $ONP_VNET_SUBNET_DEF_NAME --query id -o tsv)
az network nic create --name uservm1nic --subnet $ONP_VNET_SUBNET_DEF_ID --resource-group $RG_ONP --location $LOCATION --public-ip-address uservm1-ip
az vm create --name uservm1 --image "MicrosoftWindowsDesktop:Windows-10:19h2-ent:18363.720.2003120536" --admin-username azrefadmin --admin-password "p&ssw0rdp&ssw0rd" --nics uservm1nic --resource-group $RG_ONP --location $LOCATION
az vm open-port --resource-group $RG_ONP --name uservm1 --port 3389
```

# 3. AKS 配置環境の準備

- VNET とサブネットの作成
- 周辺サービスの作成と引き込み
  - ACR の作成と Private Endpoint 引き込み
  - TODO: SQL Database 作成と Private Endpoint 引き込み
- Azure Firewall の作成
  - Firewall の作成
  - UDR の設定
  - ルールの作成

```bash
# Variables
AKS_VNET_NAME="${NAME_PREFIX}-aks-vnet"
AKS_VNET_ADDRESS_PREFIXES=10.0.0.0/8
 
AKS_VNET_SUBNET_PES_NAME=PrivateEndpointSubnet
AKS_VNET_SUBNET_PES_ADDRESS_PREFIX=10.15.0.0/16
AKS_VNET_SUBNET_PLS_NAME=PrivateLinkServiceNatSubnet
AKS_VNET_SUBNET_PLS_ADDRESS_PREFIX=10.12.0.0/16
AKS_VNET_SUBNET_IES_NAME=InternalEndpointSubnet
AKS_VNET_SUBNET_IES_ADDRESS_PREFIX=10.11.0.0/16
AKS_VNET_SUBNET_NPS_NAME=NodepoolSubnet
AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX=10.1.0.0/16
AKS_VNET_SUBNET_FWS_NAME=AzureFirewallSubnet
AKS_VNET_SUBNET_FWS_ADDRESS_PREFIX=10.31.0.0/16
AKS_VNET_SUBNET_BTS_NAME=AzureBastionSubnet
AKS_VNET_SUBNET_BTS_ADDRESS_PREFIX=10.30.0.0/16
AKS_VNET_SUBNET_MMS_NAME=ManagementSubnet
AKS_VNET_SUBNET_MMS_ADDRESS_PREFIX=10.16.0.0/16
 
ACR_NAME="${NAME_PREFIX}aksacr"
 
FW_NAME="${NAME_PREFIX}-aks-fw"
FW_PUBLIC_IP_NAME="${FW_NAME}-ip"
FW_IP_CONFIG="${FW_NAME}-config"
 
AKS_UDR_NAME="${NAME_PREFIX}-udr"
 
# Create VNET & Subnets
az network vnet create --resource-group $RG_AKS --name $AKS_VNET_NAME --address-prefixes $AKS_VNET_ADDRESS_PREFIXES
 
AKS_VNET_ID=$(az network vnet show --resource-group $RG_AKS --name $AKS_VNET_NAME --query id -o tsv)
DIAG_STORAGE_ID=$(az storage account show --name $DIAG_STORAGE_NAME --query id -o tsv)
DIAG_LA_WS_ID=$(az monitor log-analytics workspace show --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --query id -o tsv)
az monitor diagnostic-settings create --name $AKS_VNET_NAME --resource $AKS_VNET_ID \
--logs    '[{"category": "VMProtectionAlerts","enabled": true}]' \
--metrics '[{"category": "AllMetrics","enabled": true}]' \
--storage-account $DIAG_STORAGE_ID --workspace $DIAG_LA_WS_ID
 
for i in "PES" "PLS" "IES" "NPS" "FWS" "BTS" "MMS"
do
  SUBNET_NAME="AKS_VNET_SUBNET_${i}_NAME"
  SUBNET_ADDRESS_PREFIX="AKS_VNET_SUBNET_${i}_ADDRESS_PREFIX"
  az network vnet subnet create --name $(eval echo '$'$SUBNET_NAME) --address-prefix $(eval echo '$'$SUBNET_ADDRESS_PREFIX) --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME
done
 
# Create ACR
DIAG_LA_WS_ID=$(az monitor log-analytics workspace show --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --query id -o tsv)
az acr create --name $ACR_NAME --resource-group $RG_AKS --location $LOCATION --sku Premium --default-action Deny --workspace $DIAG_LA_WS_ID
 
# Create PrivateLink to ACR
# ※ Preview 手順なので変更の可能性あり（特に DNS レコード作成まわり、現在はほぼ手作業）
# https://docs.microsoft.com/ja-jp/azure/container-registry/container-registry-private-link#set-up-private-link---cli
az network vnet subnet update --name $AKS_VNET_SUBNET_PES_NAME --vnet-name $AKS_VNET_NAME --resource-group $RG_AKS --disable-private-endpoint-network-policies
az network private-dns zone create --resource-group $RG_AKS --name "privatelink.azurecr.io"
az network private-dns link vnet create --resource-group $RG_AKS --zone-name "privatelink.azurecr.io" --name $AKS_VNET_NAME --virtual-network $AKS_VNET_NAME --registration-enabled false
ACR_ID=$(az acr show --name $ACR_NAME --query 'id' --output tsv)
az network private-endpoint create --name $ACR_NAME --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --subnet $AKS_VNET_SUBNET_PES_NAME --private-connection-resource-id $ACR_ID --group-ids registry --connection-name $ACR_NAME
 
ACR_PE_NETWORKINTERFACE_ID=$(az network private-endpoint show --name $ACR_NAME --resource-group $RG_AKS --query 'networkInterfaces[0].id' --output tsv)
 
# azrefarcacr.privatelink.azurecr.io の A レコード作成
ACR_PE_PRIVATE_IP=$(az resource show --ids $ACR_PE_NETWORKINTERFACE_ID --api-version 2019-04-01 --query 'properties.ipConfigurations[1].properties.privateIPAddress' --output tsv)
az network private-dns record-set a create --name $ACR_NAME --zone-name privatelink.azurecr.io --resource-group $RG_AKS
az network private-dns record-set a add-record --record-set-name $ACR_NAME --zone-name privatelink.azurecr.io --resource-group $RG_AKS --ipv4-address $ACR_PE_PRIVATE_IP
 
# azrefarcacr.japaneast.privatelink.azurecr.io の A レコード作成
ACR_PE_DATA_ENDPOINT_PRIVATE_IP=$(az resource show --ids $ACR_PE_NETWORKINTERFACE_ID --api-version 2019-04-01 --query 'properties.ipConfigurations[0].properties.privateIPAddress' --output tsv)
az network private-dns record-set a create --name ${ACR_NAME}.${LOCATION}.data --zone-name privatelink.azurecr.io --resource-group $RG_AKS
az network private-dns record-set a add-record --record-set-name ${ACR_NAME}.${LOCATION}.data --zone-name privatelink.azurecr.io --resource-group $RG_AKS --ipv4-address $ACR_PE_DATA_ENDPOINT_PRIVATE_IP
 
# Service Endpoint for SQL DB
# (SQL DB が新規作成できないため、Private Endpoint での構成ができない。)
# (今回のデモでは既存の SQL DB に対して Service Endpoint を利用して接続することとした。)
az network vnet subnet update --name $AKS_VNET_SUBNET_NPS_NAME --vnet-name $AKS_VNET_NAME --resource-group $RG_AKS --service-endpoints Microsoft.SQL
 
# Create Azure Firewall
# ※ VNET, Public IP, Firewall は同一 RG 内にある必要があるため、RG_AKS 内に作成
 
az network firewall create --name $FW_NAME --resource-group $RG_AKS --location $LOCATION
az network public-ip create --name $FW_PUBLIC_IP_NAME --resource-group $RG_AKS --location $LOCATION --allocation-method static --sku standard
az network firewall ip-config create --firewall-name $FW_NAME --name $FW_IP_CONFIG --public-ip-address $FW_PUBLIC_IP_NAME --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME
az network firewall update --name $FW_NAME --resource-group $RG_AKS
 
FW_ID=$(az network firewall show --name $FW_NAME --resource-group $RG_AKS --query id -o tsv)
DIAG_STORAGE_ID=$(az storage account show --name $DIAG_STORAGE_NAME --query id -o tsv)
DIAG_LA_WS_ID=$(az monitor log-analytics workspace show --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --query id -o tsv)
az monitor diagnostic-settings create --name $FW_NAME --resource $FW_ID \
--logs    '[{"category": "AzureFirewallApplicationRule","enabled": true},{"category": "AzureFirewallNetworkRule","enabled": true}]' \
--metrics '[{"category": "AllMetrics","enabled": true}]' \
--storage-account $DIAG_STORAGE_ID --workspace $DIAG_LA_WS_ID
 
FW_PRIVATE_IP_ADDRESS="$(az network firewall ip-config list -g $RG_AKS -f $FW_NAME --query "[?name=='${FW_IP_CONFIG}'].privateIpAddress" --output tsv)"
 
# Create UDR and assign UDR
az network route-table create -g $RG_AKS --name $AKS_UDR_NAME
az network route-table route create --resource-group $RG_AKS --name default --route-table-name $AKS_UDR_NAME --address-prefix 0.0.0.0/0 --next-hop-type VirtualAppliance --next-hop-ip-address $FW_PRIVATE_IP_ADDRESS
 
AKS_VNET_SUBNET_NPS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_NPS_NAME --query id -o tsv)
az network vnet subnet update --resource-group $RG_AKS --route-table $AKS_UDR_NAME --ids $AKS_VNET_SUBNET_NPS_ID
 
# Create Firewall Rules
# 最新情報は以下を参照、作成作業にはかなり時間がかかる
# https://docs.microsoft.com/en-us/azure/aks/limit-egress-traffic
# 同一 Collection Name に複数のエントリを入れる場合、最初の一件は Priority, Action を付与。2 件目以降は付与しない。
# Private Endpoint で対応できないリソースがあることに注意
 
# Required for private AKS cluster
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" --priority 200 --action "Allow" \
--name "MCR" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "*.cdn.mscr.io" "mcr.microsoft.com" "*.data.mcr.microsoft.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "k8s GET PUT operations" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "management.azure.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Azure AD authentication" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "login.microsoftonline.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "apt-get operation for Moby, PowerShell, Azure CLI" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "packages.microsoft.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "repository for kubenet, Azure CNI" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "acs-mirror.azureedge.net"
 
# ntp.ubuntu.com -> *
az network firewall network-rule create --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" --priority 200 --action "Allow" \
--name "NTP time synchronization on Linux nodes" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" \
--destination-addresses "*" --protocols "UDP" --destination-ports 123
 
# Optional for AKS cluster
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS --action "Allow" \
--priority 300 --collection-name "Optional_for_AKS_clusters" \
--name "download security patches for linux nodes" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols http=80 \
--target-fqdns "security.ubuntu.com" "azure.archive.ubuntu.com" "changelogs.ubuntu.com"
 
# Azure Monitor for containers
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS --action "Allow" \
--priority 400 --collection-name "Azure_Monitor_for_containers" \
--name "Correct data, authentication, for agent" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "dc.services.visualstudio.com" "*.ods.opinsights.azure.com" "*.oms.opinsights.azure.com" "*.microsoftonline.com" "*.monitoring.azure.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Azure_Monitor_for_containers" \
--name "kubeapi-proxy" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "aks-kubeapi-proxy-prod.trafficmanager.net"
 
# Azure Policy for AKS clusters (preview, subject to be changed)
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS --action "Allow" \
--priority 500 --collection-name "Azure_Policy_for_AKS_clusters_preview" \
--name "correct metrics and monitoring telemetry" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "gov-prod-policy-data.trafficmanager.net" "raw.githubusercontent.com" "*.gk.${LOCATION}.azmk8s.io" "dc.services.visualstudio.com"
 
# (参考) 他によく必要になる URL (AKS ツール)
# download.opensuse.org,packages.microsoft.com,dc.services.visualstudio.com,*.opinsights.azure.com,*.monitoring.azure.com,gov-prod-policy-data.trafficmanager.net,apt.dockerproject.org,nvidia.github.io
# (参考) 他によく必要になる URL (OS update)
# download.opensuse.org,*.ubuntu.com,packages.microsoft.com,snapcraft.io,api.snapcraft.io
 ```

 # 4. AKS 管理マシンの準備

- 管理用 VM の作成
  - 管理マシン（Windows）の作成 → #4.1
  - 管理マシン（Linux）の作成 → #4.2
  - （参考）Bastion の作成

## (参考) Bastion 作成 (時間がかかる)

```bash
BASTION_NAME="${NAME_PREFIX}-bastion"
BASTION_PUBLIC_IP_NAME="${BASTION_NAME}-ip"
 
az network public-ip create --name $BASTION_PUBLIC_IP_NAME --resource-group $RG_AKS --sku Standard
az network bastion create --name $BASTION_NAME --public-ip-address $BASTION_PUBLIC_IP_NAME --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --location $LOCATION
```

## 4-a. Windowsマシンの作成

- オンプレへの接続経路を持つ
- OS として Win10 を利用
  - k8s ダッシュボードの利用にモダンブラウザが必要なため
  - Win2019 + Chrome でも可
- 利用できるツール
  - az
  - kubectl
  - helm
  - Azure Portal
  - VS Code

```bash
# Parameters
MGMT_WIN_VM_NAME=mgmt-win-vm1
MGMT_WIN_VM_NIC_NAME="${MGMT_WIN_VM_NAME}-nic"
MGMT_WIN_VM_ILB_NAME="${MGMT_WIN_VM_NAME}-ilb"
 
# UDR でルートを塞ぐ
AKS_VNET_SUBNET_MMS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_MMS_NAME --query id -o tsv)
az network vnet subnet update --resource-group $RG_AKS --route-table $AKS_UDR_NAME --ids $AKS_VNET_SUBNET_MMS_ID
 
# ILB 配下に VM を作成
# NIC を先に作成（IP アドレスを確定させる）
az network lb create --resource-group $RG_AKS --name $MGMT_WIN_VM_ILB_NAME --frontend-ip-name frontend --private-ip-address 10.16.0.100 --backend-pool-name backendpool --vnet-name $AKS_VNET_NAME --subnet $AKS_VNET_SUBNET_MMS_NAME --sku Standard
az network lb probe create --resource-group $RG_AKS --lb-name $MGMT_WIN_VM_ILB_NAME --name probe --protocol tcp --port 3389
az network lb rule create --resource-group $RG_AKS --lb-name $MGMT_WIN_VM_ILB_NAME --name RDPRule --protocol tcp --frontend-port 3389 --backend-port 3389 --frontend-ip-name frontend --backend-pool-name backendpool --probe-name probe
AKS_VNET_SUBNET_MMS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_MMS_NAME --query id -o tsv)
az network nic create --name $MGMT_WIN_VM_NIC_NAME --subnet $AKS_VNET_SUBNET_MMS_ID --resource-group $RG_AKS --location $LOCATION  --lb-name $MGMT_WIN_VM_ILB_NAME --lb-address-pools backendpool
 
MGMT_WIN_NIC_IP_ADDRESS=$(az network nic show --name $MGMT_WIN_VM_NIC_NAME --resource-group $RG_AKS --query ipConfigurations[0].privateIpAddress -o tsv)
 
# Azure Firewall ルールで Windows Update などを許可
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" --priority 1000 --action "Allow" \
--name "WindowsUpdate" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--fqdn-tags "WindowsUpdate" "WindowsDiagnostics" "MicrosoftActiveProtectionService"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "Azure AD Authentication" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "login.microsoftonline.com" "aadcdn.msftauth.net" "msft.sts.microsoft.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "az command" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "management.azure.com" "aka.ms" "azurecliprod.blob.core.windows.net"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "kubectl" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "*.kubernetes.io" "storage.googleapis.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "SmartScreen" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "*.smartscreen.microsoft.com" "urs.microsoft.com" "unitedstates.smartscreen-prod.microsoft.com" "*.urs.microsoft.com" "checkappexec.microsoft.com" "wdcpalt.microsoft.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "InternetConnectTest" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "www.msftconnecttest.com"
 
# Azure Portal
# https://docs.microsoft.com/ja-jp/azure/azure-portal/azure-portal-safelist-urls?tabs=public-cloud
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "AzurePortal" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "*.aadcdn.microsoftonline-p.com" "*.aka.ms" "*.applicationinsights.io" "*.azure.com" "*.azure.net" "*.azureafd.net" "*.azure-api.net" "*.azuredatalakestore.net" "*.azureedge.net" "*.loganalytics.io" "*.microsoft.com" "*.microsoftonline.com" "*.microsoftonline-p.com" "*.msauth.net" "*.msftauth.net" "*.trafficmanager.net" "*.visualstudio.com" "*.windows.net" "*.windows-int.net"
 
# よく使われるもの一覧 : az vm image list -o table
# デスクトップ OS 一覧 : az vm image list --offer Windows-10 --all --location japaneast --publisher MicrosoftWindowsDesktop -o table
 
# Create mgmt-win-vm1
 
# VM 作成 (Windows 2019)
az vm create --name $MGMT_WIN_VM_NAME --image Win2019Datacenter --admin-username azrefadmin --admin-password "p&ssw0rdp&ssw0rd" --nics $MGMT_WIN_VM_NIC_NAME --resource-group $RG_AKS --location $LOCATION
az vm open-port --resource-group $RG_AKS --name $MGMT_WIN_VM_NAME --port 3389
 
# Private Link Service 作成
az network vnet subnet update --name $AKS_VNET_SUBNET_PLS_NAME --vnet-name $AKS_VNET_NAME --resource-group $RG_AKS --disable-private-link-service-network-policies true
MGMT_WIN_VM_ILB_FRONTEND_IP_CONFIG_ID=$(az network lb frontend-ip list --lb-name $MGMT_WIN_VM_ILB_NAME --resource-group $RG_AKS --query [0].id -o tsv)
AKS_VNET_SUBNET_PLS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_PLS_NAME --query id -o tsv)
az network private-link-service create --lb-frontend-ip-configs $MGMT_WIN_VM_ILB_FRONTEND_IP_CONFIG_ID --name $MGMT_WIN_VM_ILB_NAME --resource-group $RG_AKS --subnet $AKS_VNET_SUBNET_PLS_ID
 
# Create Private Endpoint to MgmtVM ILB
az network vnet subnet update --resource-group $RG_ONP --vnet-name $ONP_VNET_NAME --name $ONP_VNET_SUBNET_IES_NAME --disable-private-endpoint-network-policies true
ONP_VNET_SUBNET_IES_ID=$(az network vnet subnet show --resource-group $RG_ONP --vnet-name $ONP_VNET_NAME --name $ONP_VNET_SUBNET_IES_NAME --query id -o tsv)
MGMT_WIN_VM_ILB_PRIVATE_LINK_SERVICE_ID=$(az network private-link-service show --name $MGMT_WIN_VM_ILB_NAME --resource-group $RG_AKS --query id -o tsv)
az network private-endpoint create --resource-group $RG_ONP --name $MGMT_WIN_VM_ILB_NAME --subnet $ONP_VNET_SUBNET_IES_ID --private-connection-resource-id $MGMT_WIN_VM_ILB_PRIVATE_LINK_SERVICE_ID --connection-name "${MGMT_WIN_VM_ILB_NAME}_ONP" --location $LOCATION
 
# Cloud Shell 上でインストールする aks バージョンを確認しておく
az aks get-versions --location $LOCATION --query 'orchestrators[?!isPreview] | [-1].orchestratorVersion' --output tsv
# → 1.16.7
```

### VM に RDP 接続してツールをインストール

- userpc1 で mstsc を起動、10.0.250.4 へ RDP 接続
 
#### 1. az CLI インストール

https://aka.ms/installazurecliwindows

インストール後、az login でログインしておく
 
#### 2. kubectl インストール

先に取得したバージョン番号に対応する kubectl を取得

```
az aks install-cli --client-version=1.16.7
```
.azure-kubectl フォルダ下にインストールされるので、ファイルをコピーする（またはパスを通す）

```
C:\Users\azrefadmin>copy %HOMEPATH%\.azure-kubectl\kubectl.exe %HOMEPATH%\kubectl.exe
```

または下記 URL からダウンロード

https://v1-16.docs.kubernetes.io/ja/docs/tasks/tools/install-kubectl/

ダウンロード後、C:\Users\<username>\ 下へコピー

https://v1-16.docs.kubernetes.io/docs/tasks/tools/install-kubectl/#install-kubectl-on-windows
 
#### 3. Helm インストール
Helm には v2 系列と v3 系列があり、v3 系列を利用すること
（v2 系列は tiller というエージェントの展開が必要でハードルが高い）

https://get.helm.sh/helm-v3.1.2-windows-amd64.zip

ダウンロードして mgmtvm1 にコピーで持ち込み

helm.exe を c:\users\<username> にコピー
 
#### 4. VS Code

https://code.visualstudio.com/download から System Installer 64bit を落としてコピーで持ち込み
 
#### 5. Google Chrome
https://www.google.com/chrome/?standalone=1&platform=win64
からオフラインインストーラをダウンロードしてコピーで持ち込み
 

## 4-b. Linuxマシンの作成

- 利用できるツール
  - az
  - kubectl
  - docker

```bash
# Parameters
MGMT_LINUX_VM_NAME=mgmt-linux-vm1
MGMT_LINUX_VM_NIC_NAME="${MGMT_LINUX_VM_NAME}-nic"
 
# UDR でルートを塞ぐ
AKS_VNET_SUBNET_MMS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_MMS_NAME --query id -o tsv)
az network vnet subnet update --resource-group $RG_AKS --route-table $AKS_UDR_NAME --ids $AKS_VNET_SUBNET_MMS_ID
 
# NIC を先に作成（IP アドレスを確定させる）
AKS_VNET_SUBNET_MMS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_MMS_NAME --query id -o tsv)
az network nic create --name $MGMT_LINUX_VM_NIC_NAME --subnet $AKS_VNET_SUBNET_MMS_ID --resource-group $RG_AKS --location $LOCATION
 
MGMT_LINUX_NIC_IP_ADDRESS=$(az network nic show --name $MGMT_LINUX_VM_NIC_NAME --resource-group $RG_AKS --query ipConfigurations[0].privateIpAddress -o tsv)
 
# Azure Firewall ルールで通信を許可
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" --priority 1100 --action "Allow" \
--name "Ubuntu Update" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "*.ubuntu.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "Snap Package Manager" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "api.snapcraft.io" "*.cdn.snapcraft.io"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "Install az CLI for Linux" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "aka.ms" "*.python.org" "azurecliprod.blob.core.windows.net" "pypi.org" "api.snapcraft.io" "*.ubuntu.com" "packages.microsoft.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "Azure AD Authentication" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "login.microsoftonline.com" "aadcdn.msftauth.net" "msft.sts.microsoft.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "Azure Management" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "management.azure.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "kubectl" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "*.kubernetes.io" "storage.googleapis.com" "packages.cloud.google.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "Docker" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "*.docker.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "MCR" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "*.cdn.mscr.io" "mcr.microsoft.com" "*.data.mcr.microsoft.com"
 
# Create mgmt-linux-vm1 (Ubuntu)
az vm create --name $MGMT_LINUX_VM_NAME --image UbuntuLTS --admin-username azrefadmin --admin-password "p&ssw0rdp&ssw0rd" --nics $MGMT_LINUX_VM_NIC_NAME --resource-group $RG_AKS --location $LOCATION --generate-ssh-keys

ssh mgmt-linux-vm1
```


#### 1. az CLI インストール

https://docs.microsoft.com/ja-jp/cli/azure/install-azure-cli-apt?view=azure-cli-latest

```bash
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
```
 
#### 2. kubectl インストール

https://v1-16.docs.kubernetes.io/ja/docs/tasks/tools/install-kubectl/

```bash
sudo apt-get update && sudo apt-get install -y apt-transport-https
curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo apt-key add -
echo "deb https://apt.kubernetes.io/ kubernetes-xenial main" | sudo tee -a /etc/apt/sources.list.d/kubernetes.list
sudo apt-get update
sudo apt-get install -y kubectl
```
 
#### 3. Docker インストール

```bash
sudo apt-get update
sudo apt-get install -y apt-transport-https ca-certificates curl software-properties-common
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
sudo add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
sudo apt-get update
sudo apt-get install -y docker-ce
sudo docker version
```
 
#### 4. Helm インストール

```bash
sudo apt-get update
sudo apt-get install snapd
sudo snap install helm --classic
```

# 5. AKS クラスタの作成

- AKS クラスタの作成
  - サービスプリンシパル作成 （※ Managed ID が GA になったらそちらを推奨）
  - TODO: AAD 統合認証の有効化
  - AKS プライベートクラスタの作成
  - 診断設定の有効化
- AKS の設定
  - ACR との接続
  - Azure Monitor の有効化
 
```bash
# Parameters
AKS_SP_NAME="${NAME_PREFIX}-aks-sp"
AKS_CLUSTER_NAME="${NAME_PREFIX}-aks"
AKS_SERVICE_CIDR=10.10.0.0/16
AKS_DNS_SERVICE_IP=10.10.0.10
 
# ================================================
# 以下は非プライベート AKS クラスタを作成する場合にのみ必要
# 非プライベート AKS クラスタの場合は Azure Firewall のルールに Master API へのアクセス経路が必要
# Master API のアドレスが未確定のため、いったん "*" 宛として TCP 22, 443, 9000, UDP 1194 を空ける
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Node-API server communication" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "*.hcp.${LOCATION}.azmk8s.io" "*.tun.${LOCATION}.azmk8s.io"
 
az network firewall network-rule create --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Node and API communication (TCP)" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" \
--destination-addresses "*" --protocols "TCP" --destination-ports 22 443 9000
 
az network firewall network-rule create --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Node and API communication (UDP)" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" \
--destination-addresses "*" --protocols "UDP" --destination-ports 1194
# ================================================
 
 
# Create AKS Cluster ==============================================
# Service Principal の ID や Credential は後から取れないため、AKS クラスタ作成までは一度に流すこと
# TODO: Azure AD 統合は Azure AD テナントの管理者の同意が必要なため、以下では実施していない
 
# Create Service Principal
# ※ 長期的には Managed ID 方式に書き換え（現在 Preview のため利用していない）
# https://docs.microsoft.com/ja-jp/azure/aks/use-managed-identity
AKS_SP_ID=$(az ad sp create-for-rbac --skip-assignment --name $AKS_SP_NAME --query appId -o tsv)
AKS_SP_SECRET=$(az ad sp credential reset --name $AKS_SP_ID --query password -o tsv)
RG_AKS_ID=$(az group show -g $RG_AKS --query id -o tsv)
az role assignment create --assignee $AKS_SP_ID --role "Network Contributor" --scope $RG_AKS_ID
 
# Prepare for creating cluster
AKS_VERSION=$(az aks get-versions --location $LOCATION --query 'orchestrators[?!isPreview] | [-1].orchestratorVersion' --output tsv)
AKS_VNET_SUBNET_NPS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_NPS_NAME --query id -o tsv)
 
# Create Private Cluster (--enable-private-cluster) ※ 時間がかかる
# 実行時に 'Bad Request' The credentials in Service PrincipalProfile were invalid エラーが出た場合には、同一コマンドを再実行する（SPN 作成とのタイムラグの関係によるもの）
# 非プライベート AKS クラスタを利用する場合は --enable-private-cluster を外す
az aks create --resource-group $RG_AKS --name $AKS_CLUSTER_NAME --vm-set-type VirtualMachineScaleSets --load-balancer-sku standard --location $LOCATION --kubernetes-version $AKS_VERSION --network-plugin azure --vnet-subnet-id $AKS_VNET_SUBNET_NPS_ID --service-cidr $AKS_SERVICE_CIDR --dns-service-ip $AKS_DNS_SERVICE_IP --docker-bridge-address 172.17.0.1/16 --generate-ssh-keys --service-principal $AKS_SP_ID --client-secret $AKS_SP_SECRET --enable-private-cluster
 
# End of Create AKS Cluster ==============================================
 
# Configure ACR for connecting from AKS
# この作業により、Service Principal に対して ACR の AcrPull ロールが付与される
az aks update --name $AKS_CLUSTER_NAME --resource-group $RG_AKS --attach-acr $ACR_NAME
 
# Azure Monitor for AKS 有効化
DIAG_LA_WS_RESOURCE_ID=$(az monitor log-analytics workspace show --resource-group $RG_OPS --workspace-name $DIAG_LA_WS_NAME --query id -o tsv)
az aks enable-addons --addons monitoring --name $AKS_CLUSTER_NAME --resource-group $RG_AKS --workspace-resource-id $DIAG_LA_WS_RESOURCE_ID
 
# AKS 診断設定の有効化
DIAG_STORAGE_ID=$(az storage account show --name $DIAG_STORAGE_NAME --query id -o tsv)
DIAG_LA_WS_ID=$(az monitor log-analytics workspace show --workspace-name $DIAG_LA_WS_NAME --resource-group $RG_OPS --query id -o tsv)
AKS_CLUSTER_ID=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RG_AKS --query id -o tsv)
az monitor diagnostic-settings create --name $AKS_CLUSTER_NAME --resource $AKS_CLUSTER_ID \
--logs    '[{"category": "kube-apiserver","enabled": true},{"category": "kube-audit","enabled": true},{"category": "kube-controller-manager","enabled": true},{"category": "kube-scheduler","enabled": true},{"category": "cluster-autoscaler","enabled": true}]' \
--metrics '[{"category": "AllMetrics","enabled": true}]' \
--storage-account $DIAG_STORAGE_ID --workspace $DIAG_LA_WS_ID
 
# ================================================
# 以下は非プライベート AKS クラスタを作成する場合にのみ必要
# Master API のアドレスが確定した段階で、Firewall のルールを更新する
 
az aks get-credentials --name $AKS_CLUSTER_NAME --resource-group $RG_AKS
AKS_CLUSTER_MASTER_API_IP_ADDRESS=$(kubectl get endpoints -o=jsonpath='{.items[?(@.metadata.name == "kubernetes")].subsets[].addresses[].ip}')
rm ~/.kube/config
 
az network firewall network-rule delete --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Node and API communication (TCP)"
az network firewall network-rule create --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Node and API communication (TCP)" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" \
--destination-addresses $AKS_CLUSTER_MASTER_API_IP_ADDRESS --protocols "TCP" --destination-ports 22 443 9000
 
az network firewall network-rule delete --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Node and API communication (UDP)"
az network firewall network-rule create --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_AKS_clusters" \
--name "Node and API communication (UDP)" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" \
--destination-addresses $AKS_CLUSTER_MASTER_API_IP_ADDRESS --protocols "UDP" --destination-ports 1194
 
# さらに管理 PC からもアクセスできるようにする
MGMT_WIN_NIC_IP_ADDRESS=$(az network nic show --name $MGMT_WIN_VM_NIC_NAME --resource-group $RG_AKS --query ipConfigurations[0].privateIpAddress -o tsv)
MGMT_LINUX_NIC_IP_ADDRESS=$(az network nic show --name $MGMT_LINUX_VM_NIC_NAME --resource-group $RG_AKS --query ipConfigurations[0].privateIpAddress -o tsv)
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "kubectl and API communication" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "*.hcp.${LOCATION}.azmk8s.io" "*.tun.${LOCATION}.azmk8s.io"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "kubectl and API communication" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "*.hcp.${LOCATION}.azmk8s.io" "*.tun.${LOCATION}.azmk8s.io"
 
# Master API 側の IP アドレス制限機能を有効化する
# https://docs.microsoft.com/ja-jp/azure/aks/api-server-authorized-ip-ranges#update-a-clusters-api-server-authorized-ip-ranges
# Azure Firewall からのアクセスのみを有効化
 
FW_PUBLIC_IP_ADDRESS=$(az network public-ip show --name $FW_PUBLIC_IP_NAME --resource-group $RG_AKS --query ipAddress -o tsv)
 
az aks update --name $AKS_CLUSTER_NAME --resource-group $RG_AKS --api-server-authorized-ip-ranges  ${FW_PUBLIC_IP_ADDRESS}/32
```

# 6. アプリケーションの配置

- kubectl/kubeconfig の準備
  - userpc1 > mgmt-win-vm1 へのログイン
  - ~/.kube/config ファイルの取得とコピー（非 admin, admin の 2 種類）
- コンテナイメージの作成 → #6.1
- アプリケーションの配置 → #6.2

```cmd
# userpc1 で mstsc を起動、10.0.250.4 へ RDP 接続
 
################################# mgmt-win-vm1 上で作業
rem cmd を開いてホームに移動
az login
rem ブラウザが開くのでログイン
rem Select Subscription
SET SUBSCRIPTION_NAME=nakama-subscription03
az account set -s %SUBSCRIPTION_NAME%
 
rem kubeconfig ファイルを取得
SET NAME_PREFIX=azrefarc7
SET RG_AKS=%NAME_PREFIX%-aks-rg
SET AKS_CLUSTER_NAME=%NAME_PREFIX%-aks
 
del .kube\config
rem clusterAdmin 用のトークン取得
az aks get-credentials --resource-group %RG_AKS% --name %AKS_CLUSTER_NAME% --admin
ren .kube\config admin-config
rem clusterUser 用のトークン取得
az aks get-credentials --resource-group %RG_AKS% --name %AKS_CLUSTER_NAME%
################################# mgmt-win-vm1 上で作業
```

# 6-1. Dockerコンテナのビルド

- アプリケーションバイナリを mgmt-windows-vm1 に持ち込み
- ファイルを mgmt-linux-vm1 に scp で転送
- mgmt-linux-vm1 でコンテナをビルドし、レジストリにプッシュ（2 つのバージョンをプッシュ）

```bash
# Cloud Shell 上で実施 ========================================
# コンテナをビルドできるようにレポジトリへの通信を解放
MGMT_LINUX_NIC_IP_ADDRESS=$(az network nic show --name $MGMT_LINUX_VM_NIC_NAME --resource-group $RG_AKS --query ipConfigurations[0].privateIpAddress -o tsv)
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "maven build" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols https=443 \
--target-fqdns "repo.maven.apache.org" "repo.spring.io" "dl.bintray.com"
 
# mgmt-windows-vm1 上で実施 ==================================
# C:\Users\azrefadmin\AzRefArc.SpringBoot 下にファイルを展開
# .mvn, src. Dockerfile, mvnw などがこのフォルダの直下に来るように
 
# mgmt-windows-vm1 のコマンドラインから以下を実行
scp -r C:\Users\azrefadmin\AzRefArc.SpringBoot azrefadmin@mgmt-linux-vm1:/home/azrefadmin/
 
# mgmt-linux-vm1 にログインし、Docker コンテナを 2 つビルドする
# ライブラリをオンデマンドダウンロードするためビルドにはそれなりに時間がかかる (5分ぐらい)
# Docker build は最後のピリオドを忘れないように
cd ~/AzRefArc.SpringBoot
sudo docker build -t azrefarc.springboot:1 .
# トップページの文字を入れ替えた v2 アプリを作っておく
cp -b index_v2.html src/main/resources/templates/index.html
sudo docker build -t azrefarc.springboot:2 .
cd ~
 
# レポジトリにプッシュ
NAME_PREFIX=azrefarc7
ACR_NAME="${NAME_PREFIX}aksacr"
sudo docker tag azrefarc.springboot:1 ${ACR_NAME}.azurecr.io/azrefarc.springboot:1
sudo docker tag azrefarc.springboot:2 ${ACR_NAME}.azurecr.io/azrefarc.springboot:2
 
# sudo docker login ${ACR_NAME}.azurecr.io だと username/password ログイン
# Azure AD 認証でログインするために、sudo az login したのちに az acr login コマンドを利用
# トークン書き込みのために sudo で実行
sudo az login
sudo az acr login --name ${ACR_NAME}.azurecr.io
 
sudo docker push ${ACR_NAME}.azurecr.io/azrefarc.springboot:1
sudo docker push ${ACR_NAME}.azurecr.io/azrefarc.springboot:2
```

# 6-2. アプリケーションの配置

- アプリケーションの配置
  - Deployment の作成
  - Service の作成
  - 展開の確認


以下、再びmgmt-win-vm1 の中で作業
- 赤字のところは適宜書き換え
 
```bash
code web_v1.yaml
 
apiVersion: v1
kind: Namespace
metadata: 
  name: azrefarc-springboot
---
apiVersion: v1
kind: Secret
metadata:
  name: web-secrets
  namespace: azrefarc-springboot
type: Opaque
data:
  SPRING_DATASOURCE_URL: amRiYzpzcWxzZXJ2ZXI6Ly9henJlZmFyYy5kYXRhYmFzZS53aW5kb3dzLm5ldDoxNDMzO2RhdGFiYXNlTmFtZT1wdWJz
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: azrefarc-springboot
spec:
  replicas: 5
  selector:
    matchLabels:
      app: web
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 100%
      maxUnavailable: 0%
  template:
    metadata:
      labels:
        app: web # the label for the pods and the deployments
    spec:
      containers:
      - name: web
        image: azrefarc7aksacr.azurecr.io/azrefarc.springboot:1
        imagePullPolicy: Always
        ports:
        - containerPort: 8080 # the application listens to this port
        env:
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            secretKeyRef:
              name: web-secrets
              key: SPRING_DATASOURCE_URL
        resources:
          requests: # minimum resources required
            cpu: 250m
            memory: 64Mi
          limits: # maximum resources allocated
            cpu: 500m
            memory: 512Mi
        livenessProbe:
          httpGet:
            port: 8080
            path: /
          initialDelaySeconds: 60
          failureThreshold: 5
          periodSeconds: 3
        readinessProbe:
          httpGet:
            port: 8080
            path: /
          failureThreshold: 1
          periodSeconds: 1
---
apiVersion: v1
kind: Service
metadata:
  name: web
  namespace: azrefarc-springboot
  annotations:
    service.beta.kubernetes.io/azure-load-balancer-internal: "true"
    service.beta.kubernetes.io/azure-load-balancer-internal-subnet: "InternalEndpointSubnet"
spec:
  selector:
    app: web
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: LoadBalancer
  loadBalancerIP: 10.11.0.10
```

作成後に

```bash
kubectl apply -f web_v1.yaml`
 
kubectl get pods --namespace azrefarc-springboot
kubectl get services --namespace azrefarc-springboot
```
 
http://10.11.0.10 へアクセスして動作確認
 
`az aks browse -n azrefarc7-aks -g azrefarc7-aks-rg` で k8s ダッシュボードを開いて確認

（ダッシュボードを開く際は、clusterAdmin のトークンが含まれる admin-config ファイルを利用）

クラスタ起動エラーなどの有無を見ることができる

 
`code web_v2.yaml` で `azrefarc.springboot:2` を利用するように修正

作成後に `kubectl apply -f web_v2.yaml`

# 7. オンプレ模倣環境から AKS アプリへの接続
   
- AKS ILB に対する Private Link Service の作成
- AKS ILB への Private Endpoint の作成
 
※ 現時点では AKS ILB の上に直接 PLS を被せているが、将来的には ILB AppGw を被せることを推奨

（ILB AppGw が Private Endpoint を未サポートであるため、現時点ではこの構成とした）

```bash
# AKS ILB に対する Private Link Service の作成
az network vnet subnet update --name $AKS_VNET_SUBNET_PLS_NAME --vnet-name $AKS_VNET_NAME --resource-group $RG_AKS --disable-private-link-service-network-policies true
 
# AKS_MANAGED_RG="MC_${RG_AKS}_${AKS_CLUSTER_NAME}_${LOCATION}"
AKS_MANAGED_RG=$(az aks show --resource-group $RG_AKS --name $AKS_CLUSTER_NAME --query nodeResourceGroup -o tsv)
 
AKS_ILB_FRONTEND_IP_CONFIG_ID=$(az network lb frontend-ip list --lb-name "kubernetes-internal" --resource-group $AKS_MANAGED_RG --query [0].id -o tsv)
AKS_VNET_SUBNET_PLS_ID=$(az network vnet subnet show --resource-group $RG_AKS --vnet-name $AKS_VNET_NAME --name $AKS_VNET_SUBNET_PLS_NAME --query id -o tsv)
az network private-link-service create --lb-frontend-ip-configs $AKS_ILB_FRONTEND_IP_CONFIG_ID --name $AKS_CLUSTER_NAME --resource-group $RG_AKS --subnet $AKS_VNET_SUBNET_PLS_ID
 
# Create Private Endpoint to AKS
az network vnet subnet update --resource-group $RG_ONP --vnet-name $ONP_VNET_NAME --name $ONP_VNET_SUBNET_IES_NAME --disable-private-endpoint-network-policies true
ONP_VNET_SUBNET_IES_ID=$(az network vnet subnet show --resource-group $RG_ONP --vnet-name $ONP_VNET_NAME --name $ONP_VNET_SUBNET_IES_NAME --query id -o tsv)
AKS_ILB_PRIVATE_LINK_SERVICE_ID=$(az network private-link-service show --name $AKS_CLUSTER_NAME --resource-group $RG_AKS --query id -o tsv)
az network private-endpoint create --resource-group $RG_ONP --name $AKS_CLUSTER_NAME --subnet $ONP_VNET_SUBNET_IES_ID --private-connection-resource-id $AKS_ILB_PRIVATE_LINK_SERVICE_ID --connection-name "${AKS_CLUSTER_NAME}_ONP" --location $LOCATION
 
```

uservm1 から http://10.0.250.5 へアクセスして利用できるかを確認

# 8. インターネットからの接続
 
- DMZ VNET の作成
- AKS ILB への Private Endpoint の作成
- Application Gateway の作成
- 可用性テストの作成
 
```bash
# Parameters
DMZ_VNET_NAME="${NAME_PREFIX}-dmz-vnet"
DMZ_VNET_ADDRESS_PREFIXES=10.0.0.0/16
DMZ_VNET_SUBNET_AGW_NAME=AppGwSubnet
DMZ_VNET_SUBNET_AGW_ADDRESS_PREFIX=10.0.0.0/24
DMZ_VNET_SUBNET_IES_NAME=InternalEndpointSubnet
DMZ_VNET_SUBNET_IES_ADDRESS_PREFIX=10.0.250.0/24
 
# Create VNET and Subnets
az network vnet create --resource-group $RG_DMZ --name $DMZ_VNET_NAME --address-prefixes $DMZ_VNET_ADDRESS_PREFIXES
az network vnet subnet create --name $DMZ_VNET_SUBNET_AGW_NAME --address-prefix $DMZ_VNET_SUBNET_AGW_ADDRESS_PREFIX --resource-group $RG_DMZ --vnet-name $DMZ_VNET_NAME
az network vnet subnet create --name $DMZ_VNET_SUBNET_IES_NAME --address-prefix $DMZ_VNET_SUBNET_IES_ADDRESS_PREFIX --resource-group $RG_DMZ --vnet-name $DMZ_VNET_NAME
 
# Create AKS Private Endpoint
az network vnet subnet update --resource-group $RG_DMZ --vnet-name $DMZ_VNET_NAME --name $DMZ_VNET_SUBNET_IES_NAME --disable-private-endpoint-network-policies true
DMZ_VNET_SUBNET_IES_ID=$(az network vnet subnet show --resource-group $RG_DMZ --vnet-name $DMZ_VNET_NAME --name $DMZ_VNET_SUBNET_IES_NAME --query id -o tsv)
AKS_ILB_PRIVATE_LINK_SERVICE_ID=$(az network private-link-service show --name $AKS_CLUSTER_NAME --resource-group $RG_AKS --query id -o tsv)
az network private-endpoint create --resource-group $RG_DMZ --name $AKS_CLUSTER_NAME --subnet $DMZ_VNET_SUBNET_IES_ID --private-connection-resource-id $AKS_ILB_PRIVATE_LINK_SERVICE_ID --connection-name "${AKS_CLUSTER_NAME}_DMZ" --location $LOCATION
 
# Create AppGateway
DMZ_AGW_NAME="${NAME_PREFIX}-aks-appgw"
DMZ_AGW_PUBLIC_IP_NAME="${DMZ_AGW_NAME}-ip"
 
az network public-ip create --resource-group $RG_DMZ --name $DMZ_AGW_PUBLIC_IP_NAME --allocation-method Static --sku Standard
# Private Endpoint の IP アドレスを取得
DMZ_AKS_ILB_PRIVATE_ENDPOINT_NIC_ID=$(az network private-endpoint show --name $AKS_CLUSTER_NAME --resource-group $RG_DMZ --query 'networkInterfaces[0].id' --output tsv)
DMZ_AKS_ILB_PRIVATE_ENDPOINT_PRIVATE_IP=$(az resource show --ids $DMZ_AKS_ILB_PRIVATE_ENDPOINT_NIC_ID --api-version 2019-04-01 --query 'properties.ipConfigurations[0].properties.privateIPAddress' --output tsv)
# AppGw 作成 ※ 時間がかかる
az network application-gateway create --name $DMZ_AGW_NAME --location $LOCATION --resource-group $RG_DMZ --capacity 2 --sku Standard_v2 --http-settings-cookie-based-affinity Enabled --public-ip-address $DMZ_AGW_PUBLIC_IP_NAME --vnet-name $DMZ_VNET_NAME --subnet $DMZ_VNET_SUBNET_AGW_NAME --servers $DMZ_AKS_ILB_PRIVATE_ENDPOINT_PRIVATE_IP
 
# 可用性テストの作成は az コマンドではできない (ARM テンプレートのみ可)
# https://docs.microsoft.com/ja-jp/azure/azure-monitor/platform/alerts-metric-create-templates#template-for-an-availability-test-along-with-a-metric-alert
# ★ 手作業で作る
# DMZ_AGW_PUBLIC_IP_ADDRESS=$(az network public-ip show --resource-group $RG_DMZ --name $DMZ_AGW_PUBLIC_IP_NAME --query ipAddress -o tsv)
```

# 9. ノード自動再起動
  
- Kured を利用してノード自動再起動の機能を付加
- helm を利用してインストールする必要があるため、mgmt-linux-vm1 または mgmt-win-vm1 上から実施
 
```bash
# Kured インストールに必要な FQDN の解放
MGMT_WIN_NIC_IP_ADDRESS=$(az network nic show --name $MGMT_WIN_VM_NIC_NAME --resource-group $RG_AKS --query ipConfigurations[0].privateIpAddress -o tsv)
MGMT_LINUX_NIC_IP_ADDRESS=$(az network nic show --name $MGMT_LINUX_VM_NIC_NAME --resource-group $RG_AKS --query ipConfigurations[0].privateIpAddress -o tsv)
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Linux_VMs" \
--name "Install Kured" --source-addresses "${MGMT_LINUX_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "kubernetes-charts.storage.googleapis.com" "gcr.io" "*.docker.io" "production.cloudflare.docker.com"
 
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_management_Windows_VMs" \
--name "Install Kured" --source-addresses "${MGMT_WIN_NIC_IP_ADDRESS}/32" --protocols http=80 https=443 \
--target-fqdns "kubernetes-charts.storage.googleapis.com" "gcr.io" "*.docker.io" "production.cloudflare.docker.com"
 
# AKS ノードがコンテナを取得するために必要な経路の解放
az network firewall application-rule create  --firewall-name $FW_NAME --resource-group $RG_AKS \
--collection-name "Required_for_Installing_Kured" --priority 600 --action "Allow" \
--name "ContainerRegistries" --source-addresses "${AKS_VNET_SUBNET_NPS_ADDRESS_PREFIX}" --protocols https=443 \
--target-fqdns "kubernetes-charts.storage.googleapis.com" "gcr.io" "*.docker.io" "production.cloudflare.docker.com"
 
# mgmt-linux-vm1 または mgmt-win-vm1 上から Kured をインストール
 
helm repo add stable https://kubernetes-charts.storage.googleapis.com/
helm repo update
kubectl create namespace kured
helm install kured stable/kured --namespace kured --set nodeSelector."beta\.kubernetes\.io/os"=linux
 
kubectl get pods --namespace kured
Ready になれば成功
失敗した場合は 以下でやり直し
helm uninstall kured --namespace kured
```