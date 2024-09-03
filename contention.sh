#!/bin/bash

workloads=("contention-solo.json" "contention-sepBankR.json" "contention-sameBankR.json" "contention-sepBankW.json" "contention-sameBankW.json" "contention-throttledR.json" "contention-throttledW.json")

OUT=Graphing-BankDefense/contention-dirs.txt
> $OUT

sed -i "48 c\\    default_hw_config: vcu118_max_mempress_4GB_2Bank_bru" deploy/config_runtime.yaml	

for i in "${workloads[@]}"; do
	echo "$i"
	sed -i "85 c\\    workload_name: $i" deploy/config_runtime.yaml
	sleep 10	
	firesim infrasetup && firesim runworkload | grep results-workload | tail -n 1 >> $OUT
done
