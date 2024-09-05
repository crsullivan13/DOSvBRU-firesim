#!/bin/bash

workload=sdvbs-all.json

OUT=Graphing-BankDefense/Fig11-dirs.txt
> $OUT

sed -i "85 c\\    workload_name: $workload" deploy/config_runtime.yaml

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_2Bank_bru" deploy/config_runtime.yaml
echo "AllBank-2Bank"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sleep 30

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_2Bank_bru-aware-test" deploy/config_runtime.yaml
echo "PerBank-2Bank"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sleep 30

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_4Bank_bru" deploy/config_runtime.yaml
echo "AllBank-4Bank"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sleep 30

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_4Bank_bru-aware-test" deploy/config_runtime.yaml
echo "PerBank-4Bank"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

