#!/bin/bash

workloads=("640.json" "1280.json" "2560.json" "5120.json" "7680.json" "10240.json" "12800.json" "15360.json")

OUT=Graphing-BankDefense/synth-budget-dirs.txt
> $OUT

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_2Bank_bru" deploy/config_runtime.yaml

for i in "${workloads[@]}"; do
	echo "base-$i"
	sed -i "85 c\\    workload_name: base-$i" deploy/config_runtime.yaml	
	firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT
done
