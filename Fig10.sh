#!/bin/bash

OUT=Graphing-BankDefense/Fig10-dirs.txt
> $OUT

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_2Bank_bru" deploy/config_runtime.yaml

echo "2bk-all"
sed -i "85 c\\    workload_name: bandwidth-1280.json" deploy/config_runtime.yaml	
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUTiresim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_2Bank_bru-aware" deploy/config_runtime.yaml

sleep 30
echo "2bk-per"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUTiresim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_4Bank_bru" deploy/config_runtime.yaml

sleep 30
echo "4bk-all"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUTiresim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT

sed -i "48 c\\    default_hw_config: vcu118_lgboom_4GB_4Bank_bru-aware" deploy/config_runtime.yaml

sleep 30
echo "4bk-per"
firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUTiresim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT
