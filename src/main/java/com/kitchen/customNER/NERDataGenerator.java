package com.kitchen.customNER;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.util.CoreMap;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.*;

public class NERDataGenerator {

    static class Ingredient {
        double amount;
        String unit;
        String ingredient;
    }
    
    static class Recipe {
        String name;
        String url;
        List<Ingredient> ingredients;
        List<String> instructions;
    }
    
    public static List<Recipe> loadRecipes(String filename) {
        try (Reader reader = new FileReader(filename)) {
            Gson gson = new Gson();
            Type recipeListType = new TypeToken<List<Recipe>>() {}.getType();
            return gson.fromJson(reader, recipeListType);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
    
    public static List<String> buildGlobalIngredientList(List<Recipe> recipes) {
        Set<String> ingredientSet = new HashSet<>();
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize,ssplit,pos");
        StanfordCoreNLP ingredientPipeline = new StanfordCoreNLP(props);
        
        for (Recipe recipe : recipes) {
            if (recipe.ingredients != null) {
                for (Ingredient ing : recipe.ingredients) {
                    String fullIngredient = ing.ingredient.toLowerCase().trim();
                    fullIngredient = fullIngredient.replaceAll("[^a-z\\s]", "").trim();
                    if (!fullIngredient.isEmpty()) {
                        ingredientSet.add(fullIngredient);
                        
                        Annotation annotation = new Annotation(fullIngredient);
                        ingredientPipeline.annotate(annotation);
                        List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);
                        if (sentences != null && !sentences.isEmpty()) {
                            List<CoreLabel> tokens = sentences.get(0).get(CoreAnnotations.TokensAnnotation.class);
                            if (tokens != null && !tokens.isEmpty()) {
                                CoreLabel lastToken = tokens.get(tokens.size() - 1);
                                String pos = lastToken.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                                if (!pos.equals("IN") && !pos.equals("TO") && !pos.startsWith("VB")) {
                                    String lastWord = lastToken.originalText().toLowerCase().trim();
                                    lastWord = lastWord.replaceAll("[^a-z]", "");
                                    if (!lastWord.isEmpty()) {
                                        ingredientSet.add(lastWord);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(ingredientSet);
    }
    
   
    public static void annotateInstructions(List<String> instructions, List<String> ingredientList,
                                            BufferedWriter bw, StanfordCoreNLP pipeline) throws IOException {
        for (String instruction : instructions) {
            Annotation document = new Annotation(instruction);
            pipeline.annotate(document);
            List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
            if (sentences == null) continue;
            
            for (CoreMap sentence : sentences) {
                List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
                for (int i = 0; i < tokens.size(); i++) {
                    CoreLabel token = tokens.get(i);
                    String tokenText = token.originalText();
                    String cleanedToken = tokenText.replaceAll("[^a-zA-Z]", "").toLowerCase();
                    String label = "O";
                    boolean multiWordMatched = false;
                    
                    for (String ingredient : ingredientList) {
                        if (ingredient.contains(" ")) {
                            String[] ingredientWords = ingredient.split("\\s+");
                            if (i <= tokens.size() - ingredientWords.length) {
                                boolean match = true;
                                for (int j = 0; j < ingredientWords.length; j++) {
                                    String wordToMatch = tokens.get(i + j).originalText().replaceAll("[^a-zA-Z]", "").toLowerCase();
                                    if (!wordToMatch.equals(ingredientWords[j])) {
                                        match = false;
                                        break;
                                    }
                                }
                                if (match) {
                                    for (int j = 0; j < ingredientWords.length; j++) {
                                        String outToken = tokens.get(i + j).originalText();
                                        String nerLabel = (j == 0) ? "B-INGREDIENT" : "I-INGREDIENT";
                                        bw.write(outToken + " " + nerLabel + "\n");
                                    }
                                    i += ingredientWords.length - 1;
                                    multiWordMatched = true;
                                    break;
                                }
                            }
                        }
                    }
                    
                    if (!multiWordMatched) {
                        for (String ingredient : ingredientList) {
                            if (!ingredient.contains(" ")) { 
                                if (cleanedToken.equals(ingredient)) {
                                    label = "B-INGREDIENT";
                                    break;
                                }
                            }
                        }
                        bw.write(tokenText + " " + label + "\n");
                    }
                }
                bw.write("\n"); 
            }
        }
    }
    
    public static void main(String[] args) {
        String inputFilename = "complete_indian_recipes.json";
        String outputFilename = "ner_training_data.txt";
        
        List<Recipe> recipes = loadRecipes(inputFilename);
        System.out.println("Loaded " + recipes.size() + " recipes.");
        
        List<String> globalIngredientList = buildGlobalIngredientList(recipes);
        System.out.println("Global ingredient list size: " + globalIngredientList.size());
        
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize,ssplit");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
        
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilename))) {
            for (Recipe recipe : recipes) {
                System.out.println("Processing recipe: " + recipe.name);
                bw.write("# Recipe: " + recipe.name + "\n");
                if (recipe.instructions != null) {
                    annotateInstructions(recipe.instructions, globalIngredientList, bw, pipeline);
                }
                bw.write("\n");
            }
            System.out.println("Annotated NER training data written to " + outputFilename);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
