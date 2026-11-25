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
import org.cloudsimplus.util.Log;
import ch.qos.logback.classic.Level;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

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

    // Parameter Eksperimen Multi-Run & Output Grafik
    private static final int MAX_RUNS = 50;
    private static final int[] CHECKPOINTS = {1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50};
    private static final String OUTPUT_DIR = "output";

    public static void main(String[] args) {
        // Matikan log INFO internal CloudSim Plus agar output 50 runs bersih dan cepat
        Log.setLevel(Level.WARN);

        List<Vm> refVms = createVms();
        List<Cloudlet> refCloudlets = loadCloudletsFromDataset(DATASET_FILE);

        System.out.println("============================================================================================");
        System.out.println("  SIMULASI SOKA (CloudSim Plus): ANALISIS MULTI-RUN SCA VS MFO (1 s.d. " + MAX_RUNS + " RUNS)");
        System.out.println("  Dataset: " + DATASET_FILE + " (" + refCloudlets.size() + " Cloudlets) | N=" + POPULATION_SIZE + ", T=" + MAX_ITERATION);
        System.out.println("============================================================================================\n");

        double sumMakespanSCA = 0, sumUtilSCA = 0, sumStdDevSCA = 0, sumEnergySCA = 0;
        double sumMakespanMFO = 0, sumUtilMFO = 0, sumStdDevMFO = 0, sumEnergyMFO = 0;

        List<CheckpointResult> checkpointResults = new ArrayList<>();
        Set<Integer> checkpointSet = new HashSet<>();
        for (int cp : CHECKPOINTS) checkpointSet.add(cp);

        for (int run = 1; run <= MAX_RUNS; run++) {
            // TAHAP 1: PENCARIAN JADWAL OPTIMAL
            OptimizationResult optSCA = runSCA(refCloudlets, refVms);
            OptimizationResult optMFO = runMFO(refCloudlets, refVms);

            // TAHAP 2: EKSEKUSI SIMULASI CLOUDSIM PLUS
            SimulationOutput simSCA = runCloudSimWithMapping(optSCA.bestPosition, false);
            SimulationOutput simMFO = runCloudSimWithMapping(optMFO.bestPosition, false);

            sumMakespanSCA += simSCA.metrics.makespan;
            sumUtilSCA += simSCA.metrics.utilization;
            sumStdDevSCA += simSCA.metrics.stdDev;
            sumEnergySCA += simSCA.metrics.energyKwh;

            sumMakespanMFO += simMFO.metrics.makespan;
            sumUtilMFO += simMFO.metrics.utilization;
            sumStdDevMFO += simMFO.metrics.stdDev;
            sumEnergyMFO += simMFO.metrics.energyKwh;

            if (checkpointSet.contains(run)) {
                MetricsResult avgSCA = new MetricsResult(
                        sumMakespanSCA / run, sumUtilSCA / run, sumStdDevSCA / run, sumEnergySCA / run);
                MetricsResult avgMFO = new MetricsResult(
                        sumMakespanMFO / run, sumUtilMFO / run, sumStdDevMFO / run, sumEnergyMFO / run);
                checkpointResults.add(new CheckpointResult(run, avgSCA, avgMFO));
                System.out.printf("Checkpoint %2d Runs selesai | Avg Makespan -> SCA: %7.2f s, MFO: %7.2f s%n",
                        run, avgSCA.makespan, avgMFO.makespan);
            }
        }

        // TAHAP 3: CETAK TABEL REKAPITULASI SETIAP CHECKPOINT (1, 5, 10, ..., 50)
        System.out.println("\n============================================================================================");
        System.out.println("  TABEL RATA-RATA 4 METRIK EVALUASI PADA SETIAP JUMLAH RUN (SCA vs MFO)");
        System.out.println("============================================================================================");
        System.out.printf("%-6s | %-10s %-10s | %-10s %-10s | %-10s %-10s | %-11s %-11s%n",
                "Runs", "SCA Mak(s)", "MFO Mak(s)", "SCA Utl(%)", "MFO Utl(%)", "SCA StdDev", "MFO StdDev", "SCA En(kWh)", "MFO En(kWh)");
        System.out.println("--------------------------------------------------------------------------------------------");
        for (CheckpointResult cp : checkpointResults) {
            System.out.printf("%-6d | %-10.2f %-10.2f | %-10.2f %-10.2f | %-10.2f %-10.2f | %-11.5f %-11.5f%n",
                    cp.runCount,
                    cp.sca.makespan, cp.mfo.makespan,
                    cp.sca.utilization, cp.mfo.utilization,
                    cp.sca.stdDev, cp.mfo.stdDev,
                    cp.sca.energyKwh, cp.mfo.energyKwh);
        }
        System.out.println("============================================================================================");

        // TAHAP 4: EKSPOR CSV & GENERATE 4 GRAFIK GARIS (.PNG)
        exportResultsAndCharts(checkpointResults, refCloudlets.size());
    }

    private static void exportResultsAndCharts(List<CheckpointResult> results, int cloudletCount) {
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));

            // 1. Simpan CSV
            File csvFile = new File(OUTPUT_DIR, "hasil_multi_run_50.csv");
            try (PrintWriter pw = new PrintWriter(csvFile, StandardCharsets.UTF_8)) {
                pw.println("Runs,SCA_Makespan_s,MFO_Makespan_s,SCA_Utilization_pct,MFO_Utilization_pct,SCA_StdDev,MFO_StdDev,SCA_Energy_kWh,MFO_Energy_kWh");
                for (CheckpointResult cp : results) {
                    pw.printf(Locale.US, "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.6f,%.6f%n",
                            cp.runCount,
                            cp.sca.makespan, cp.mfo.makespan,
                            cp.sca.utilization, cp.mfo.utilization,
                            cp.sca.stdDev, cp.mfo.stdDev,
                            cp.sca.energyKwh, cp.mfo.energyKwh);
                }
            }

            int n = results.size();
            int[] xRuns = new int[n];
            double[] scaMak = new double[n], mfoMak = new double[n];
            double[] scaUtl = new double[n], mfoUtl = new double[n];
            double[] scaStd = new double[n], mfoStd = new double[n];
            double[] scaEng = new double[n], mfoEng = new double[n];

            for (int i = 0; i < n; i++) {
                CheckpointResult cp = results.get(i);
                xRuns[i] = cp.runCount;
                scaMak[i] = cp.sca.makespan;    mfoMak[i] = cp.mfo.makespan;
                scaUtl[i] = cp.sca.utilization; mfoUtl[i] = cp.mfo.utilization;
                scaStd[i] = cp.sca.stdDev;      mfoStd[i] = cp.mfo.stdDev;
                scaEng[i] = cp.sca.energyKwh;   mfoEng[i] = cp.mfo.energyKwh;
            }

            String subtitle = "Dataset: " + DATASET_FILE + " (" + cloudletCount + " Cloudlets) | Populasi=" + POPULATION_SIZE + ", Iterasi=" + MAX_ITERATION;

            saveLineChart(new File(OUTPUT_DIR, "1_grafik_makespan.png"),
                    "Perbandingan Rata-rata Makespan (SCA vs MFO)", subtitle,
                    "Rata-rata Makespan (Detik)", xRuns, scaMak, mfoMak, "%.1f");

            saveLineChart(new File(OUTPUT_DIR, "2_grafik_utilization.png"),
                    "Perbandingan Rata-rata Resource Utilization (SCA vs MFO)", subtitle,
                    "Rata-rata Resource Utilization (%)", xRuns, scaUtl, mfoUtl, "%.1f%%");

            saveLineChart(new File(OUTPUT_DIR, "3_grafik_load_balancing.png"),
                    "Perbandingan Rata-rata Load Balancing StdDev (SCA vs MFO)", subtitle,
                    "Rata-rata Standard Deviation", xRuns, scaStd, mfoStd, "%.1f");

            saveLineChart(new File(OUTPUT_DIR, "4_grafik_energi.png"),
                    "Perbandingan Rata-rata Konsumsi Energi (SCA vs MFO)", subtitle,
                    "Rata-rata Konsumsi Energi (kWh)", xRuns, scaEng, mfoEng, "%.4f");

            System.out.println("\n[SUKSES] File CSV dan 4 grafik PNG telah disimpan di folder: " + new File(OUTPUT_DIR).getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ERROR] Gagal menyimpan grafik/CSV: " + e.getMessage());
        }
    }

    private static void saveLineChart(File file, String title, String subtitle, String yLabel,
                                      int[] xRuns, double[] scaY, double[] mfoY, String yFmt) throws Exception {
        int width = 960, height = 560;
        int left = 100, right = 45, top = 80, bottom = 75;
        int plotW = width - left - right;
        int plotH = height - top - bottom;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background putih
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        // Hitung min & max Y dengan padding 12%
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < xRuns.length; i++) {
            minY = Math.min(minY, Math.min(scaY[i], mfoY[i]));
            maxY = Math.max(maxY, Math.max(scaY[i], mfoY[i]));
        }
        double range = Math.max(1e-6, maxY - minY);
        double yMin = Math.max(0.0, minY - range * 0.15);
        double yMax = maxY + range * 0.15;

        // Judul & Subjudul
        g.setColor(new Color(30, 30, 30));
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        FontMetrics fmTitle = g.getFontMetrics();
        g.drawString(title, (width - fmTitle.stringWidth(title)) / 2, 32);

        g.setColor(new Color(100, 100, 100));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        FontMetrics fmSub = g.getFontMetrics();
        g.drawString(subtitle, (width - fmSub.stringWidth(subtitle)) / 2, 52);

        // Grid & Label Sumbu Y (5 garis)
        int yTicks = 5;
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        FontMetrics fmAxis = g.getFontMetrics();
        for (int i = 0; i <= yTicks; i++) {
            double val = yMin + (yMax - yMin) * i / yTicks;
            int py = top + plotH - (int) Math.round((val - yMin) / (yMax - yMin) * plotH);
            g.setColor(new Color(230, 230, 230));
            g.drawLine(left, py, left + plotW, py);
            g.setColor(new Color(60, 60, 60));
            String lbl = String.format(Locale.US, yFmt, val);
            g.drawString(lbl, left - fmAxis.stringWidth(lbl) - 10, py + 4);
        }

        // Grid & Label Sumbu X (1, 5, 10, ..., 50)
        int xMin = xRuns[0], xMax = xRuns[xRuns.length - 1];
        for (int xVal : xRuns) {
            int px = left + (int) Math.round((double) (xVal - xMin) / (xMax - xMin) * plotW);
            g.setColor(new Color(240, 240, 240));
            g.drawLine(px, top, px, top + plotH);
            g.setColor(new Color(60, 60, 60));
            String lbl = String.valueOf(xVal);
            g.drawString(lbl, px - fmAxis.stringWidth(lbl) / 2, top + plotH + 20);
        }

        // Garis Sumbu Utama
        g.setColor(new Color(80, 80, 80));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(left, top, plotW, plotH);

        // Label Sumbu X & Y
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        String xTitle = "Jumlah Run (Percobaan)";
        g.drawString(xTitle, left + (plotW - g.getFontMetrics().stringWidth(xTitle)) / 2, height - 18);

        AffineTransform orig = g.getTransform();
        g.rotate(-Math.PI / 2);
        g.drawString(yLabel, -(top + plotH / 2 + g.getFontMetrics().stringWidth(yLabel) / 2), 24);
        g.setTransform(orig);

        // Gambar Garis SCA (Biru) & MFO (Merah)
        Color colorSCA = new Color(31, 119, 180);
        Color colorMFO = new Color(214, 39, 40);
        drawSeries(g, xRuns, scaY, xMin, xMax, yMin, yMax, left, top, plotW, plotH, colorSCA);
        drawSeries(g, xRuns, mfoY, xMin, xMax, yMin, yMax, left, top, plotW, plotH, colorMFO);

        // Legend di kanan atas area plot
        int legX = left + plotW - 140, legY = top + 12;
        g.setColor(new Color(255, 255, 255, 230));
        g.fillRoundRect(legX, legY, 128, 50, 8, 8);
        g.setColor(new Color(200, 200, 200));
        g.drawRoundRect(legX, legY, 128, 50, 8, 8);

        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(colorSCA);
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(legX + 12, legY + 17, legX + 36, legY + 17);
        g.fillOval(legX + 20, legY + 13, 8, 8);
        g.setColor(new Color(40, 40, 40));
        g.drawString("SCA", legX + 44, legY + 21);

        g.setColor(colorMFO);
        g.drawLine(legX + 12, legY + 36, legX + 36, legY + 36);
        g.fillOval(legX + 20, legY + 32, 8, 8);
        g.setColor(new Color(40, 40, 40));
        g.drawString("MFO", legX + 44, legY + 40);

        g.dispose();
        ImageIO.write(img, "png", file);
    }

    private static void drawSeries(Graphics2D g, int[] xRuns, double[] yVals,
                                   int xMin, int xMax, double yMin, double yMax,
                                   int left, int top, int plotW, int plotH, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2.5f));
        int prevX = -1, prevY = -1;
        for (int i = 0; i < xRuns.length; i++) {
            int px = left + (int) Math.round((double) (xRuns[i] - xMin) / (xMax - xMin) * plotW);
            int py = top + plotH - (int) Math.round((yVals[i] - yMin) / (yMax - yMin) * plotH);
            if (i > 0) {
                g.drawLine(prevX, prevY, px, py);
            }
            prevX = px;
            prevY = py;
        }
        for (int i = 0; i < xRuns.length; i++) {
            int px = left + (int) Math.round((double) (xRuns[i] - xMin) / (xMax - xMin) * plotW);
            int py = top + plotH - (int) Math.round((yVals[i] - yMin) / (yMax - yMin) * plotH);
            g.setColor(Color.WHITE);
            g.fillOval(px - 5, py - 5, 10, 10);
            g.setColor(color);
            g.fillOval(px - 4, py - 4, 8, 8);
        }
    }

    // =========================================================================
    // EKSEKUSI SIMULASI CLOUDSIM PLUS BERDASARKAN HASIL PEMETAAN
    // =========================================================================
    private static SimulationOutput runCloudSimWithMapping(double[] mapping, boolean printTable) {
        CloudSim simulation = new CloudSim(0.01);
        Datacenter datacenter = createDatacenter(simulation);
        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);
        broker.setVmDestructionDelay(10.0);

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
        double[] convergenceCurve = new double[MAX_ITERATION + 1];

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

            convergenceCurve[t] = flames.get(0).fitness;

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
        }
        return new OptimizationResult(flames.get(0).position, convergenceCurve);
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
        double[] convergenceCurve = new double[MAX_ITERATION + 1];

        for (int t = 1; t <= MAX_ITERATION; t++) {
            // 1. Evaluasi fitness & perbarui Destination Point (P)
            for (Agent agent : population) {
                agent.fitness = evaluatePosition(agent.position, cloudlets, vms).makespan;
                if (agent.fitness < globalBest.fitness) {
                    globalBest.position = agent.position.clone();
                    globalBest.fitness = agent.fitness;
                }
            }

            convergenceCurve[t] = globalBest.fitness;

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
        }
        return new OptimizationResult(globalBest.position, convergenceCurve);
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
    static class CheckpointResult {
        int runCount;
        MetricsResult sca;
        MetricsResult mfo;

        CheckpointResult(int runCount, MetricsResult sca, MetricsResult mfo) {
            this.runCount = runCount;
            this.sca = sca;
            this.mfo = mfo;
        }
    }

    static class OptimizationResult {
        double[] bestPosition;
        double[] convergenceCurve;

        OptimizationResult(double[] bestPosition, double[] convergenceCurve) {
            this.bestPosition = bestPosition;
            this.convergenceCurve = convergenceCurve;
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