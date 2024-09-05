#!/bin/bash

OUT=Graphing-BankDefense/Fig9-dirs.txt
> $OUT

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_2Bank_bru" deploy/config_runtime.yaml

echo "all-bank"
sed -i "85 c\\    workload_name: contention-throttledW.json" deploy/config_runtime.yaml	
sleep 30
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_2Bank_bru-aware" deploy/config_runtime.yaml

echo "per-bank"
sleep 30
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

