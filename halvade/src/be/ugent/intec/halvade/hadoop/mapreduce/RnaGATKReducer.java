/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package be.ugent.intec.halvade.hadoop.mapreduce;

import be.ugent.intec.halvade.tools.GATKTools;
import be.ugent.intec.halvade.tools.PreprocessingTools;
import be.ugent.intec.halvade.tools.QualityException;
import be.ugent.intec.halvade.utils.ChromosomeRange;
import be.ugent.intec.halvade.utils.Logger;
import be.ugent.intec.halvade.utils.HalvadeConf;
import be.ugent.intec.halvade.utils.HalvadeFileUtils;
import be.ugent.intec.halvade.utils.SAMRecordIterator;
import java.io.File;
import org.seqdoop.hadoop_bam.SAMRecordWritable;
import java.io.IOException;
import java.net.URISyntaxException;
import org.apache.hadoop.conf.Configuration;

/**
 *
 * @author ddecap
 */
public class RnaGATKReducer extends GATKReducer {
    
    @Override
    protected void processAlignments(Iterable<SAMRecordWritable> values, Context context, PreprocessingTools tools, GATKTools gatk) throws IOException, InterruptedException, URISyntaxException, QualityException {
        long startTime = System.currentTimeMillis();
        // remove stargen2 after map steps!
        removeStarGen2IfPresent(context.getConfiguration());
        // temporary files
        String region = tmpFileBase + "-region.intervals";
        String preprocess = tmpFileBase + "-1.bam";
        String tmpFile1 = tmpFileBase + "-2.bam";
        String tmpFile2 = tmpFileBase + "-3.bam";
        String tmpFile3 = tmpFileBase + "-4.bam";
        String snps = tmpFileBase + (outputGVCF ? ".g.vcf" : ".vcf");
        
        boolean useElPrep = HalvadeConf.getUseElPrep(context.getConfiguration());
        ChromosomeRange r = new ChromosomeRange();
        SAMRecordIterator SAMit = new SAMRecordIterator(values.iterator(), header, r, fixQualEnc);
        
        if(useElPrep && isFirstAttempt)
            elPrepPreprocess(context, tools, SAMit, preprocess);
        else {
            if(!isFirstAttempt) Logger.DEBUG("attempt " + taskId + ", preprocessing with Picard for smaller peak memory");
            PicardPreprocess(context, tools, SAMit, preprocess);
        }
        region = makeRegionFile(context, r, tools, region);
        if(region == null) return;
        
        splitNTrim(context, region, gatk, preprocess, tmpFile1, true);
        
        
        String bqsrinput = tmpFile1;
        if (doIndelRealignment) {
            indelRealignment(context, region, gatk, tmpFile1, tmpFile2);  
            bqsrinput = tmpFile2;
        }
        
        if(skipBQSR) {    
            RnaVariantCalling(context, region, gatk, bqsrinput, snps);     
        } else {
            baseQualityScoreRecalibration(context, region, r, tools, gatk, bqsrinput, tmpFile3);        
            RnaVariantCalling(context, region, gatk, tmpFile3, snps);           
        }
        
//        // filter/annotate??       
//        windows = 35;
//        cluster = 3;
//        minFS = 30.0;
//        maxQD = 2.0;
//        String filteredSnps = tmpFileBase + "-filtered.vcf";    
//        String annotatedSnps = tmpFileBase + "-annotated.vcf";
//        annotateVariants(context, region, gatk, snps, annotatedSnps);
//        filterVariants(context, region, gatk, annotatedSnps, filteredSnps);   
        
        variantFiles.add(snps);
         
        HalvadeFileUtils.removeLocalFile(region);
        long estimatedTime = System.currentTimeMillis() - startTime;
        Logger.DEBUG("total estimated time: " + estimatedTime / 1000);
    }
    
    private void removeStarGen2IfPresent(Configuration conf) {
        String refDir = HalvadeConf.getScratchTempDir(conf);
        String pass2uid = HalvadeConf.getPass2UID(conf);
        File starGen = new File(refDir + pass2uid);
        if(starGen.isDirectory()) {
            HalvadeFileUtils.removeLocalDir(keep, starGen.getAbsolutePath());
        }
    }
    
}