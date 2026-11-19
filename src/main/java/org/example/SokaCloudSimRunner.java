package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.ResourceProvisionerSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SokaCloudSimRunner {

    // Pilih nama file dataset di folder src/main/resources/
    private static final String DATASET_FILE = "GoCJ_Dataset_100.txt";

    // Parameter Infrastruktur
    private static final int HOST_COUNT = 4;
    private static final double HOST_MAX_POWER_WATT = 250.0;
    private static final int VM_COUNT = 8;

    // Parameter Metaheuristik
    private static final int POPULATION_SIZE = 30; // Ukuran Populasi (N)
    private static final int MAX_ITERATION = 100;  // Iterasi Maksimum (T)

    public static void main(String[] args) {
        List<Vm> refVms = createVms();
        List<Cloudlet> refCloudlets = loadCloudletsFromDataset(DATASET_FILE);

        // =============================================================
        // TAHAP 1: PENCARIAN JADWAL OPTIMAL (KANONIK SESUAI PAPER)
        // =============================================================
        OptimizationResult optSCA = runSCA(refCloudlets, refVms);
        OptimizationResult optMFO = runMFO(refCloudlets, refVms);

        // =============================================================
        // TAHAP 2: EKSEKUSI SIMULASI CLOUDSIM & CETAK SIMULATION RESULTS
        // =============================================================
        System.out.println("=================================================================");
        System.out.println("  SIMULASI SOKA (CloudSim Plus): PERBANDINGAN SCA VS MFO");
        System.out.println("  Dataset: " + DATASET_FILE + " (" + refCloudlets.size() + " Cloudlets)");
        System.out.println("=================================================================\n");

        SimulationOutput simSCA = runCloudSimWithMapping(optSCA.bestPosition, false);
        SimulationOutput simMFO = runCloudSimWithMapping(optMFO.bestPosition, true);

        // =============================================================
        // TAHAP 3: CETAK PROGRES ITERASI & HASIL EVALUASI DI PALING BAWAH
        // =============================================================
        System.out.println("\n=================================================================");
        System.out.println("  PROGRES KONVERGENSI ALGORITMA (" + MAX_ITERATION + " Iterasi)");
        System.out.println("=================================================================");
        System.out.println("[1] Sine Cosine Algorithm (SCA - Mirjalili, 2016):");
        for (String log : optSCA.logs) {
            System.out.println(log);
        }

        System.out.println("\n[2] Moth-Flame Optimization (MFO - Mirjalili, 2015):");
        for (String log : optMFO.logs) {
            System.out.println(log);
        }

        MetricsResult metricsSCA = simSCA.metrics;
        MetricsResult metricsMFO = simMFO.metrics;

        System.out.println("\n=================================================================");
        System.out.println("  HASIL EVALUASI KOMPARASI (SCA vs MFO)");
        System.out.println("=================================================================");
        System.out.printf("%-28s | %-14s | %-14s%n", "Metrik Evaluasi", "SCA", "MFO");
        System.out.println("-----------------------------------------------------------------");
        System.out.printf("%-28s | %-14s | %-14s%n", "Makespan (Detik) [v]",
                String.format("%.2f s", metricsSCA.makespan), String.format("%.2f s", metricsMFO.makespan));
        System.out.printf("%-28s | %-14s | %-14s%n", "Resource Utilization [^]",
                String.format("%.2f %%", metricsSCA.utilization), String.format("%.2f %%", metricsMFO.utilization));
        System.out.printf("%-28s | %-14s | %-14s%n", "Load Balancing (StdDev) [v]",
                String.format("%.2f", metricsSCA.stdDev), String.format("%.2f", metricsMFO.stdDev));
        System.out.printf("%-28s | %-14s | %-14s%n", "Konsumsi Energi (kWh) [v]",
                String.format("%.5f kWh", metricsSCA.energyKwh), String.format("%.5f kWh", metricsMFO.energyKwh));
        System.out.println("=================================================================");

        if (metricsMFO.makespan <= metricsSCA.makespan) {
            System.out.println("KESIMPULAN: MFO lebih optimal dengan selisih Makespan "
                    + String.format("%.2f detik.", (metricsSCA.makespan - metricsMFO.makespan)));
        } else {
            System.out.println("KESIMPULAN: SCA lebih optimal dengan selisih Makespan "
                    + String.format("%.2f detik.", (metricsMFO.makespan - metricsSCA.makespan)));
        }
    }

    // =========================================================================
    // EKSEKUSI SIMULASI CLOUDSIM PLUS BERDASARKAN HASIL PEMETAAN
    // =========================================================================
    private static SimulationOutput runCloudSimWithMapping(double[] mapping, boolean printTable) {
        CloudSim simulation = new CloudSim();
        Datacenter datacenter = createDatacenter(simulation);
        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);

        List<Vm> vmList = createVms();
        List<Cloudlet> cloudletList = loadCloudletsFromDataset(DATASET_FILE);

        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudletList);

        for (int i = 0; i < cloudletList.size(); i++) {
            int vmIdx = decodeToVmIndex(mapping[i], vmList.size());
            broker.bindCloudletToVm(cloudletList.get(i), vmList.get(vmIdx));
        }

        simulation.start();

        List<Cloudlet> finishedList = broker.getCloudletFinishedList();
        if (printTable) {
            new CloudletsTableBuilder(finishedList).build();
        }

        MetricsResult metrics = calculateMetricsFromFinished(finishedList, vmList.size());
        return new SimulationOutput(finishedList, metrics);
    }

    private static MetricsResult calculateMetricsFromFinished(List<Cloudlet> finishedList, int vmCount) {
        double[] vmLoads = new double[vmCount];

        for (Cloudlet c : finishedList) {
            int vmId = (int) c.getVm().getId();
            if (vmId >= 0 && vmId < vmCount) {
                double execTime = (double) c.getLength() / c.getVm().getMips();
                vmLoads[vmId] += execTime;
            }
        }

        return computeMetricsFromLoads(vmLoads);
    }

    // =========================================================================
    // PEMBACA DATASET DARI FOLDER RESOURCES
    // =========================================================================
    private static List<Cloudlet> loadCloudletsFromDataset(String fileName) {
        List<Cloudlet> cloudlets = new ArrayList<>();
        InputStream inputStream = SokaCloudSimRunner.class.getClassLoader().getResourceAsStream(fileName);

        if (inputStream == null) {
            throw new RuntimeException("File dataset '" + fileName + "' tidak ditemukan di src/main/resources/!");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int id = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                long length = Math.max(1L, Math.round(Double.parseDouble(line)));

                Cloudlet cloudlet = new CloudletSimple(id++, length, 1);
                cloudlet.setFileSize(300)
                        .setOutputSize(300)
                        .setUtilizationModel(new UtilizationModelFull());
                cloudlets.add(cloudlet);
            }
        } catch (Exception e) {
            throw new RuntimeException("Gagal membaca dataset " + fileName + ": " + e.getMessage(), e);
        }

        return cloudlets;
    }

    // =========================================================================
    // ALGORITMA 1: Moth-Flame Optimization (Kanonik - Mirjalili, 2015)
    // =========================================================================
    private static OptimizationResult runMFO(List<Cloudlet> cloudlets, List<Vm> vms) {
        int dim = cloudlets.size();
        double maxBound = vms.size() - 1.0;
        List<Agent> moths = initPopulation(dim, vms.size());
        List<Agent> flames = new ArrayList<>();
        List<String> logs = new ArrayList<>();

        for (int t = 1; t <= MAX_ITERATION; t++) {
            for (Agent moth : moths) {
                moth.fitness = evaluatePosition(moth.position, cloudlets, vms).makespan;
            }

            if (t == 1) {
                flames = copyPopulation(moths);
                Collections.sort(flames);
            } else {
                List<Agent> merged = new ArrayList<>();
                merged.addAll(moths);
                merged.addAll(flames);
                Collections.sort(merged);
                flames = new ArrayList<>(merged.subList(0, POPULATION_SIZE));
            }

            // Eq. (3.14): Jumlah flame berkurang secara adaptif sepanjang iterasi
            int flameNo = (int) Math.round(POPULATION_SIZE - t * ((POPULATION_SIZE - 1.0) / MAX_ITERATION));
            // Parameter konvergensi 'a' menurun linear dari -1 ke -2
            double a = -1.0 + t * ((-1.0) / MAX_ITERATION);

            for (int i = 0; i < POPULATION_SIZE; i++) {
                int targetIdx = Math.min(i, Math.max(0, flameNo - 1));
                for (int d = 0; d < dim; d++) {
                    double targetFlame = flames.get(targetIdx).position[d];
                    double distanceToFlame = Math.abs(targetFlame - moths.get(i).position[d]);
                    double b = 1.0;
                    double r = (a - 1.0) * Math.random() + 1.0;

                    // Eq. (3.12): Pergerakan Spiral Logaritmik MFO
                    double newPos = distanceToFlame * Math.exp(b * r) * Math.cos(2.0 * Math.PI * r) + targetFlame;
                    moths.get(i).position[d] = Math.max(0.0, Math.min(maxBound, newPos));
                }
            }

            if (t % 20 == 0 || t == 1) {
                logs.add(String.format("  Iterasi %3d -> Best Makespan MFO: %.2f detik", t, flames.get(0).fitness));
            }
        }
        return new OptimizationResult(flames.get(0).position, logs);
    }

    // =========================================================================
    // ALGORITMA 2: Sine Cosine Algorithm (Kanonik - Mirjalili, 2016)
    // =========================================================================
    private static OptimizationResult runSCA(List<Cloudlet> cloudlets, List<Vm> vms) {
        int dim = cloudlets.size();
        double maxBound = vms.size() - 1.0;
        List<Agent> population = initPopulation(dim, vms.size());
        Agent globalBest = new Agent(dim);
        globalBest.fitness = Double.MAX_VALUE;
        List<String> logs = new ArrayList<>();

        for (int t = 1; t <= MAX_ITERATION; t++) {
            // 1. Evaluasi fitness & perbarui Destination Point (P)
            for (Agent agent : population) {
                agent.fitness = evaluatePosition(agent.position, cloudlets, vms).makespan;
                if (agent.fitness < globalBest.fitness) {
                    globalBest.position = agent.position.clone();
                    globalBest.fitness = agent.fitness;
                }
            }

            // 2. Eq. (3.4): r1 menurun linear dari a = 2 menuju 0
            double a = 2.0;
            double r1 = a - t * (a / MAX_ITERATION);

            // 3. Eq. (3.1) & (3.2): Update posisi semua agen di seluruh dimensi
            for (Agent agent : population) {
                for (int d = 0; d < dim; d++) {
                    double r2 = 2.0 * Math.PI * Math.random();
                    double r3 = 2.0 * Math.random();
                    double r4 = Math.random();

                    double newPos;
                    if (r4 < 0.5) {
                        newPos = agent.position[d] + r1 * Math.sin(r2) * Math.abs(r3 * globalBest.position[d] - agent.position[d]);
                    } else {
                        newPos = agent.position[d] + r1 * Math.cos(r2) * Math.abs(r3 * globalBest.position[d] - agent.position[d]);
                    }
                    agent.position[d] = Math.max(0.0, Math.min(maxBound, newPos));
                }
            }

            if (t % 20 == 0 || t == 1) {
                logs.add(String.format("  Iterasi %3d -> Best Makespan SCA: %.2f detik", t, globalBest.fitness));
            }
        }
        return new OptimizationResult(globalBest.position, logs);
    }

    // =========================================================================
    // DECODING & KALKULASI 4 METRIK EVALUASI
    // =========================================================================
    private static int decodeToVmIndex(double continuousValue, int vmCount) {
        int idx = (int) Math.round(continuousValue);
        return Math.max(0, Math.min(vmCount - 1, idx));
    }

    private static MetricsResult evaluatePosition(double[] position, List<Cloudlet> cloudlets, List<Vm> vms) {
        double[] vmLoads = new double[vms.size()];

        for (int i = 0; i < position.length; i++) {
            int vmIndex = decodeToVmIndex(position[i], vms.size());
            double execTime = (double) cloudlets.get(i).getLength() / vms.get(vmIndex).getMips();
            vmLoads[vmIndex] += execTime;
        }

        return computeMetricsFromLoads(vmLoads);
    }

    private static MetricsResult computeMetricsFromLoads(double[] vmLoads) {
        double makespan = 0.0;
        double totalExecTime = 0.0;
        for (double load : vmLoads) {
            if (load > makespan) makespan = load;
            totalExecTime += load;
        }

        double avgLoad = totalExecTime / vmLoads.length;
        double utilization = (makespan > 0) ? (avgLoad / makespan) * 100.0 : 0.0;

        double varianceSum = 0.0;
        for (double load : vmLoads) {
            varianceSum += Math.pow(load - avgLoad, 2);
        }
        double stdDev = Math.sqrt(varianceSum / vmLoads.length);

        double pIdle = 0.6 * HOST_MAX_POWER_WATT;
        double utilRatio = utilization / 100.0;
        double avgHostPowerWatt = pIdle + (HOST_MAX_POWER_WATT - pIdle) * utilRatio;
        double energyKwh = (HOST_COUNT * avgHostPowerWatt * makespan) / 3_600_000.0;

        return new MetricsResult(makespan, utilization, stdDev, energyKwh);
    }

    // =========================================================================
    // MODEL DATA & BOILERPLATE CLOUDSIM PLUS
    // =========================================================================
    static class OptimizationResult {
        double[] bestPosition;
        List<String> logs;

        OptimizationResult(double[] bestPosition, List<String> logs) {
            this.bestPosition = bestPosition;
            this.logs = logs;
        }
    }

    static class SimulationOutput {
        List<Cloudlet> finishedList;
        MetricsResult metrics;

        SimulationOutput(List<Cloudlet> finishedList, MetricsResult metrics) {
            this.finishedList = finishedList;
            this.metrics = metrics;
        }
    }

    static class MetricsResult {
        double makespan;
        double utilization;
        double stdDev;
        double energyKwh;

        MetricsResult(double makespan, double utilization, double stdDev, double energyKwh) {
            this.makespan = makespan;
            this.utilization = utilization;
            this.stdDev = stdDev;
            this.energyKwh = energyKwh;
        }
    }

    static class Agent implements Comparable<Agent> {
        double[] position;
        double fitness;

        Agent(int dim) {
            this.position = new double[dim];
            this.fitness = Double.MAX_VALUE;
        }

        @Override
        public int compareTo(Agent other) {
            return Double.compare(this.fitness, other.fitness);
        }
    }

    private static List<Agent> initPopulation(int dim, int vmCount) {
        List<Agent> pop = new ArrayList<>();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            Agent a = new Agent(dim);
            for (int d = 0; d < dim; d++) {
                a.position[d] = Math.random() * (vmCount - 1);
            }
            pop.add(a);
        }
        return pop;
    }

    private static List<Agent> copyPopulation(List<Agent> source) {
        List<Agent> copy = new ArrayList<>();
        for (Agent s : source) {
            Agent a = new Agent(s.position.length);
            a.position = s.position.clone();
            a.fitness = s.fitness;
            copy.add(a);
        }
        return copy;
    }

    private static Datacenter createDatacenter(CloudSim simulation) {
        List<Host> hostList = new ArrayList<>();
        long mips = 10000;
        long ram = 16384;
        long storage = 1000000;
        long bw = 10000;

        for (int i = 0; i < HOST_COUNT; i++) {
            List<Pe> peList = new ArrayList<>();
            peList.add(new PeSimple(mips, new PeProvisionerSimple()));
            peList.add(new PeSimple(mips, new PeProvisionerSimple()));

            Host host = new HostSimple(ram, bw, storage, peList);
            host.setRamProvisioner(new ResourceProvisionerSimple())
                    .setBwProvisioner(new ResourceProvisionerSimple())
                    .setVmScheduler(new VmSchedulerTimeShared());
            hostList.add(host);
        }

        return new DatacenterSimple(simulation, hostList);
    }

    private static List<Vm> createVms() {
        List<Vm> vms = new ArrayList<>();
        long[] mipsList = {1000, 1000, 2000, 3000, 2500, 4000, 4000, 4000};
        for (int i = 0; i < VM_COUNT; i++) {
            Vm vm = new VmSimple(i, mipsList[i], 1);
            vm.setRam(1024)
                    .setBw(1000)
                    .setSize(10000)
                    .setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vms.add(vm);
        }
        return vms;
    }
}