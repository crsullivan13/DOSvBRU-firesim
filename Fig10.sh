#!/bin/bash

workloads=("1280.json")

OUT=Graphing-BankDefense/Fig10-dirs.txt
> $OUT

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_2Bank_bru" deploy/config_runtime.yaml

echo "2bk-all"
sed -i "85 c\\    workload_name: base-$i" deploy/config_runtime.yaml	
fsed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_2Bank_bru" deploy/config_runtime.yaml

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_2Bank_bru-aware" deploy/config_runtime.yaml

echo "2bk-per"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUTiresim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_4Bank_bru" deploy/config_runtime.yaml

echo "4bk-all"
sed -i "85 c\\    workload_name: base-$i" deploy/config_runtime.yaml	
fsed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_2Bank_bru" deploy/config_runtime.yaml

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_4Bank_bru-aware" deploy/config_runtime.yaml

echo "4bk-per"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUTiresim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT
