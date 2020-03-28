#!/bin/bash

if [ "$#" -lt 5 ]; then
    echo "Usage: ./Create_WCP_namespaces.sh
          <First WCP Namespace count>
          <Last WCP Namespace count>
          <storage-policy to be attached to namespace>
          <naming convention of the namespace>
          <WCP Cluster Name>"
    exit 1
fi

begin=$1
end=$2
storage_policy=$3
namespace_prefix=$4
cluster_name=$5

sp=`dcli +show-unreleased +username 'Administrator@vsphere.local' +password 'Admin!23' com vmware vcenter storage policies list | grep ${storage_policy} | cut -d'|' -f4`
echo "Storage policy ID = $sp"

cluster_id=`dcli  +show-unreleased +username 'Administrator@vsphere.local' +password 'Admin!23' com vmware vcenter cluster list | grep ${cluster_name} | grep True | cut -d'|' -f3`
echo "Cluster ID = $cluster_id"

echo "Creating WCP namespaces with attached Storage Profile :$storage_policy"


for (( i=$begin; i <= $end; i++ ))
do
    output=`dcli +show-unreleased +username 'Administrator@vsphere.local' +password 'Admin!23' com vmware vcenter namespaces instances create --cluster $cluster_id --namespace ${namespace_prefix}${i} --storage-specs "[{\"policy\": \"$sp\"}]"`
    echo $output
done
